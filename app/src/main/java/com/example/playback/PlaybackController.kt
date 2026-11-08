package com.example.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.SubtitleView

interface PlaybackUiBridge {
    fun onNativePlaybackBuffering(message: String? = null)
    fun onNativePlaybackReady()
    fun onNativePlaybackEnded()
    fun onNativePlaybackStateChanged(isPlaying: Boolean)
    fun onNativePlaybackError(message: String)
    fun onExternalAudioBuffering(message: String? = null)
    fun onExternalAudioPlaying()
    fun onExternalAudioError(message: String)
    fun onExternalAudioStopped()
    fun onExternalAudioDetached()
}

@OptIn(UnstableApi::class)
class PlaybackController(
    private val context: Context,
    private val containerProvider: () -> ViewGroup?,
    private val uiBridge: PlaybackUiBridge,
    private val userAgent: String = "SONIX-TV/1.0"
) {
    val settings = PlaybackSettings(context)

    private val audioFocusManager = SonixAudioFocusManager(
        context = context,
        onAudioFocusLost = { transient ->
            Log.w(TAG, "Audio focus lost (transient=$transient)")
            pauseVideoAndExternal()
        },
        onAudioFocusGained = {
            Log.i(TAG, "Audio focus gained; resuming playback")
            resumeVideoAndExternal()
        },
        onAudioDuck = { duck ->
            if (duck) {
                currentEngine?.setVolume(0.2f)
            } else {
                currentEngine?.setVolume(videoVolumeBeforeExternalAudio)
            }
        }
    )

    private var currentEngine: VideoEngine? = null
    private var currentEngineType: VideoEngineType = VideoEngineType.AUTO
    private val externalAudioController = ExternalAudioController(context, userAgent)

    private var lastUrl: String = ""
    private var lastStreamType: String = "live"
    private var lastHeaders: Map<String, String> = emptyMap()
    private var cornerRadiusPx: Float = 0f

    private var videoVolumeBeforeExternalAudio = 1f
    private var isUserMuted = false

    init {
        externalAudioController.setListener(object : ExternalAudioListener {
            override fun onExternalAudioBuffering(message: String?) {
                uiBridge.onExternalAudioBuffering(message)
            }

            override fun onExternalAudioReady() {
                // External audio confirmed ready and playing:
                // NOW we safely mute the video engine without touching its playback state!
                currentEngine?.let { engine ->
                    if (!engine.isMuted()) {
                        videoVolumeBeforeExternalAudio = engine.getVolume()
                    }
                    engine.setMuted(true)
                }
                uiBridge.onExternalAudioPlaying()
                SonixDiagnosticLog.logExternalAudioEvent(
                    state = "PLAYING",
                    stationName = "ExternalAudio",
                    url = "",
                    generation = 0L,
                    attempt = 0
                )
            }

            override fun onExternalAudioError(message: String) {
                // External audio failed: restore original video audio safely
                if (!isUserMuted) {
                    currentEngine?.setMuted(false)
                    currentEngine?.setVolume(videoVolumeBeforeExternalAudio)
                }
                uiBridge.onExternalAudioError(message)
                SonixDiagnosticLog.logExternalAudioEvent(
                    state = "ERROR",
                    stationName = "ExternalAudio",
                    url = "",
                    generation = 0L,
                    attempt = 0,
                    error = message
                )
            }

            override fun onExternalAudioStopped() {
                // External audio stopped: restore original video audio safely
                if (!isUserMuted) {
                    currentEngine?.setMuted(false)
                    currentEngine?.setVolume(videoVolumeBeforeExternalAudio)
                }
                uiBridge.onExternalAudioStopped()
                uiBridge.onExternalAudioDetached()
            }
        })
    }

    fun play(url: String, headers: Map<String, String> = emptyMap(), streamType: String = "live") {
        lastUrl = url
        lastHeaders = headers
        lastStreamType = streamType

        val selected = settings.getSelectedEngine()
        val effective = settings.resolveEffectiveEngine(selected)

        // Ensure audio focus
        audioFocusManager.requestPlaybackFocus()

        val engine = getOrCreateEngine(effective)
        engine.play(url, headers, streamType)

        SonixDiagnosticLog.logPlaybackSession(
            engine = effective,
            url = url,
            streamType = streamType,
            videoWidth = 0,
            videoHeight = 0,
            frameRate = 0f,
            decoderName = null,
            playbackState = "PREPARING",
            playWhenReady = true,
            isPlaying = true,
            suppressionReason = "NONE",
            audioFocusGranted = audioFocusManager.hasAudioFocus(),
            externalAudioActive = externalAudioController.isExternalAudioActive(),
            bufferingDurationMs = 0L,
            droppedFrames = 0L
        )
    }

    fun getView(): View? = currentEngine?.getView()

    fun updateLayoutParams(params: ViewGroup.LayoutParams) {
        currentEngine?.getView()?.layoutParams = params
    }

    private fun getOrCreateEngine(targetType: VideoEngineType): VideoEngine {
        val existing = currentEngine
        if (existing != null && currentEngineType == targetType) {
            return existing
        }

        // Release old engine to guarantee no two video engines run concurrently
        existing?.let { old ->
            Log.i(TAG, "Releasing existing engine ${old.engineType.name} before starting ${targetType.name}")
            old.stop()
            old.release()
            currentEngine = null
        }

        val container = containerProvider() ?: throw IllegalStateException("Player container not available")
        val created: VideoEngine = try {
            when (targetType) {
                VideoEngineType.LIBMPV -> MpvVideoEngine(context, userAgent)
                VideoEngineType.MEDIA3, VideoEngineType.AUTO -> Media3VideoEngine(context, userAgent)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed creating engine $targetType; falling back to Media3", t)
            SonixDiagnosticLog.logEngineSwitch(targetType, VideoEngineType.MEDIA3, "Engine creation failed: ${t.message}")
            Media3VideoEngine(context, userAgent)
        }

        created.setListener(createEngineListener(created))
        created.initialize(container, cornerRadiusPx)

        currentEngine = created
        currentEngineType = targetType
        return created
    }

    private fun createEngineListener(engine: VideoEngine): VideoEngineListener {
        return object : VideoEngineListener {
            override fun onPlaybackBuffering() {
                uiBridge.onNativePlaybackBuffering()
            }

            override fun onPlaybackReady() {
                uiBridge.onNativePlaybackReady()
            }

            override fun onPlaybackEnded() {
                uiBridge.onNativePlaybackEnded()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                uiBridge.onNativePlaybackStateChanged(isPlaying)
            }

            override fun onPlaybackError(message: String, isFatal: Boolean, canFallbackToBackup: Boolean) {
                Log.e(TAG, "Engine ${engine.engineType} error: $message (fatal=$isFatal, fallback=$canFallbackToBackup)")
                if (canFallbackToBackup && engine.engineType == VideoEngineType.LIBMPV) {
                    Log.i(TAG, "Executing automatic safe fallback from libmpv to Media3")
                    SonixDiagnosticLog.logEngineSwitch(
                        fromEngine = VideoEngineType.LIBMPV,
                        toEngine = VideoEngineType.MEDIA3,
                        reason = "Fallback on error: $message"
                    )
                    switchToEngine(VideoEngineType.MEDIA3, resumePlayback = true)
                    return
                }
                uiBridge.onNativePlaybackError(message)
            }

            override fun onVideoSizeChanged(width: Int, height: Int) {
                Log.d(TAG, "Video size changed: ${width}x${height}")
            }
        }
    }

    fun switchToEngine(newEngineType: VideoEngineType, resumePlayback: Boolean = true) {
        if (currentEngineType == newEngineType && currentEngine != null) return

        val wasPlaying = currentEngine?.isPlaying() == true
        val savedPosition = currentEngine?.getCurrentPosition() ?: 0L

        currentEngine?.stop()
        currentEngine?.release()
        currentEngine = null

        if (resumePlayback && lastUrl.isNotEmpty()) {
            val engine = getOrCreateEngine(newEngineType)
            engine.play(lastUrl, lastHeaders, lastStreamType)
            if (savedPosition > 0L) {
                engine.seekTo(savedPosition)
            }
            if (!wasPlaying) {
                engine.pause()
            }
        }
    }

    fun pause() {
        currentEngine?.pause()
        if (externalAudioController.isExternalAudioActive()) {
            externalAudioController.pause()
        }
    }

    fun resume() {
        audioFocusManager.requestPlaybackFocus()
        currentEngine?.resume()
        if (externalAudioController.isExternalAudioActive()) {
            externalAudioController.resume()
        }
    }

    private fun pauseVideoAndExternal() {
        currentEngine?.pause()
        if (externalAudioController.isExternalAudioActive()) {
            externalAudioController.pause()
        }
    }

    private fun resumeVideoAndExternal() {
        currentEngine?.resume()
        if (externalAudioController.isExternalAudioActive()) {
            externalAudioController.resume()
        }
    }

    fun stop() {
        currentEngine?.stop()
        externalAudioController.detachExternalAudio(notifyListener = false)
        audioFocusManager.abandonPlaybackFocus()
    }

    fun release() {
        currentEngine?.release()
        currentEngine = null
        externalAudioController.release()
        audioFocusManager.abandonPlaybackFocus()
    }

    fun seekTo(positionMs: Long) {
        currentEngine?.seekTo(positionMs)
    }

    fun seekBy(offsetMs: Long) {
        currentEngine?.seekBy(offsetMs)
    }

    fun setMuted(muted: Boolean) {
        isUserMuted = muted
        if (externalAudioController.isExternalAudioActive()) {
            // Keep video audio muted while external audio is active
            currentEngine?.setMuted(true)
        } else {
            currentEngine?.setMuted(muted)
        }
    }

    fun setVolume(volume: Float) {
        videoVolumeBeforeExternalAudio = volume.coerceIn(0f, 1f)
        if (!externalAudioController.isExternalAudioActive()) {
            currentEngine?.setVolume(videoVolumeBeforeExternalAudio)
        }
    }

    fun setVideoQuality(maxHeight: Int) {
        currentEngine?.setVideoQuality(maxHeight)
    }

    fun isPlaying(): Boolean = currentEngine?.isPlaying() ?: false

    fun isActive(): Boolean = currentEngine?.isActive() ?: false

    fun getCurrentPosition(): Long = currentEngine?.getCurrentPosition() ?: 0L

    fun getDuration(): Long = currentEngine?.getDuration() ?: 0L

    fun isSeekable(): Boolean = currentEngine?.isSeekable() ?: false

    fun setCornerRadius(radiusPx: Float) {
        cornerRadiusPx = radiusPx
        currentEngine?.setCornerRadius(radiusPx)
    }

    fun getActiveSubtitleView(): SubtitleView? = currentEngine?.getSubtitleView()

    // External Audio integration
    fun attachExternalAudio(url: String) {
        externalAudioController.attachExternalAudio(url)
    }

    fun detachExternalAudio() {
        externalAudioController.detachExternalAudio(notifyListener = true)
    }

    fun setExternalAudioVolume(volume: Float) {
        externalAudioController.setExternalAudioVolume(volume)
    }

    fun setExternalAudioSyncOffset(offsetMs: Long) {
        externalAudioController.setExternalAudioSyncOffset(offsetMs)
    }

    fun pauseExternalAudio() {
        externalAudioController.pause()
    }

    fun resumeExternalAudio() {
        externalAudioController.resume()
    }

    fun isExternalAudioActive(): Boolean = externalAudioController.isExternalAudioActive()

    companion object {
        private const val TAG = "PlaybackController"
    }
}
