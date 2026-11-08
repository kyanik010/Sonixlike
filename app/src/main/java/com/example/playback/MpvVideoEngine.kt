package com.example.playback

import android.content.Context
import android.graphics.Outline
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.media3.ui.SubtitleView
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MpvVideoEngine(
    private val context: Context,
    private val userAgent: String = "SONIX-TV/1.0"
) : VideoEngine {

    override val engineType: VideoEngineType = VideoEngineType.LIBMPV

    private var mpv: MPV? = null
    private var textureView: TextureView? = null
    private var attachedSurface: Surface? = null
    private var listener: VideoEngineListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isEngineActive = false
    private var isEnginePlaying = false
    private var isMuted = false
    private var savedVolume = 1f
    private var activeUrl = ""
    private var activeStreamType = "live"
    private var cornerRadiusPx = 0f

    private var currentDurationMs = 0L
    private var currentPositionMs = 0L
    private var engineScope: CoroutineScope? = null

    override fun initialize(container: ViewGroup, initialCornerRadiusPx: Float): View {
        cornerRadiusPx = initialCornerRadiusPx
        val view = textureView ?: TextureView(context).also { textureView = it }

        view.apply {
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx.coerceAtLeast(0f))
                }
            }
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                    attachedSurface?.release()
                    val surface = Surface(st)
                    attachedSurface = surface
                    try {
                        mpv?.attachSurface(surface)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed attaching surface to mpv", e)
                    }
                }

                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    try {
                        mpv?.detachSurface()
                    } catch (e: Throwable) {
                        Log.w(TAG, "Failed detaching surface from mpv", e)
                    }
                    attachedSurface?.release()
                    attachedSurface = null
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }

        if (view.parent !== container) {
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, 0)
        }
        return view
    }

    override fun getView(): View? = textureView

    override fun detachView() {
        textureView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        attachedSurface?.release()
        attachedSurface = null
        textureView = null
    }

    override fun setCornerRadius(radiusPx: Float) {
        cornerRadiusPx = radiusPx
        textureView?.invalidateOutline()
    }

    override fun play(url: String, headers: Map<String, String>, streamType: String) {
        activeUrl = url
        activeStreamType = streamType
        isEngineActive = true

        try {
            ensureMpvInitialized()
            val player = mpv ?: throw IllegalStateException("MPV instance could not be created")

            // Configure volume
            val volumeInt = if (isMuted) 0 else (savedVolume * 100).toInt().coerceIn(0, 100)
            player.setPropertyInt("volume", volumeInt)

            if (headers.isNotEmpty()) {
                val headerString = headers.entries.joinToString(",") { "${it.key}: ${it.value}" }
                player.setOptionString("http-header-fields", headerString)
            }

            listener?.onPlaybackBuffering()
            player.command("loadfile", url)
            player.setPropertyBoolean("pause", false)
            isEnginePlaying = true
            listener?.onIsPlayingChanged(true)
        } catch (t: Throwable) {
            Log.e(TAG, "Error starting MPV playback for $url", t)
            isEngineActive = false
            isEnginePlaying = false
            listener?.onPlaybackError(
                t.localizedMessage ?: "فشل تشغيل الفيديو عبر محرك libmpv",
                isFatal = true,
                canFallbackToBackup = true
            )
        }
    }

    private fun ensureMpvInitialized() {
        if (mpv != null) return

        val player = MPV()
        player.create(context.applicationContext)

        // Hardware Decoding via Android MediaCodec
        player.setOptionString("hwdec", "mediacodec")
        player.setOptionString("hwdec-codecs", "all")
        player.setOptionString("vo", "gpu")
        player.setOptionString("gpu-context", "android")

        // Network & User Agent
        player.setOptionString("user-agent", userAgent)
        player.setOptionString("tls-verify", "no")

        if (activeStreamType.equals("live", ignoreCase = true)) {
            player.setOptionString("cache", "yes")
            player.setOptionString("demuxer-max-bytes", "32MiB")
            player.setOptionString("demuxer-readahead-secs", "5")
        }

        player.init()
        mpv = player

        // Attach surface if texture view already created
        attachedSurface?.let { surface ->
            try {
                player.attachSurface(surface)
            } catch (e: Throwable) {
                Log.w(TAG, "Error attaching surface to newly created mpv", e)
            }
        }

        startObservingProperties(player)
    }

    private fun startObservingProperties(player: MPV) {
        engineScope?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        engineScope = scope

        scope.launch {
            try {
                player.propFlow.getBooleanFlow("pause").collectLatest { isPaused ->
                    if (isPaused != null) {
                        isEnginePlaying = !isPaused
                        listener?.onIsPlayingChanged(!isPaused)
                    }
                }
            } catch (_: Throwable) {}
        }

        scope.launch {
            try {
                player.propFlow.getDoubleFlow("duration").collectLatest { durationSec ->
                    if (durationSec != null) {
                        currentDurationMs = (durationSec * 1000.0).toLong().coerceAtLeast(0L)
                    }
                }
            } catch (_: Throwable) {}
        }

        scope.launch {
            try {
                player.propFlow.getDoubleFlow("time-pos").collectLatest { timeSec ->
                    if (timeSec != null) {
                        currentPositionMs = (timeSec * 1000.0).toLong().coerceAtLeast(0L)
                    }
                }
            } catch (_: Throwable) {}
        }

        scope.launch {
            try {
                player.propFlow.getBooleanFlow("eof-reached").collectLatest { eof ->
                    if (eof == true) {
                        isEnginePlaying = false
                        listener?.onPlaybackEnded()
                    }
                }
            } catch (_: Throwable) {}
        }

        scope.launch {
            try {
                player.propFlow.getBooleanFlow("paused-for-cache").collectLatest { buffering ->
                    if (buffering == true) {
                        listener?.onPlaybackBuffering()
                    } else if (buffering == false) {
                        listener?.onPlaybackReady()
                    }
                }
            } catch (_: Throwable) {}
        }

        scope.launch {
            try {
                player.propFlow.getIntFlow("video-params/w").collectLatest { width ->
                    val w = width ?: 0
                    val h = player.getPropertyInt("video-params/h") ?: 0
                    if (w > 0 && h > 0) {
                        listener?.onVideoSizeChanged(w, h)
                    }
                }
            } catch (_: Throwable) {}
        }
    }

    override fun pause() {
        try {
            mpv?.setPropertyBoolean("pause", true)
            isEnginePlaying = false
        } catch (e: Throwable) {
            Log.w(TAG, "MPV pause failed", e)
        }
    }

    override fun resume() {
        try {
            mpv?.setPropertyBoolean("pause", false)
            isEnginePlaying = true
        } catch (e: Throwable) {
            Log.w(TAG, "MPV resume failed", e)
        }
    }

    override fun stop() {
        isEngineActive = false
        isEnginePlaying = false
        try {
            mpv?.command("stop")
        } catch (e: Throwable) {
            Log.w(TAG, "MPV stop failed", e)
        }
    }

    override fun release() {
        stop()
        engineScope?.cancel()
        engineScope = null
        try {
            mpv?.detachSurface()
            mpv?.destroy()
        } catch (e: Throwable) {
            Log.w(TAG, "MPV destroy failed", e)
        }
        mpv = null
        detachView()
    }

    override fun seekTo(positionMs: Long) {
        val seconds = (positionMs.coerceAtLeast(0L) / 1000.0)
        try {
            mpv?.command("seek", seconds.toString(), "absolute")
        } catch (e: Throwable) {
            Log.w(TAG, "MPV seekTo failed", e)
        }
    }

    override fun seekBy(offsetMs: Long) {
        val seconds = (offsetMs / 1000.0)
        try {
            mpv?.command("seek", seconds.toString(), "relative")
        } catch (e: Throwable) {
            Log.w(TAG, "MPV seekBy failed", e)
        }
    }

    override fun setVolume(volume: Float) {
        savedVolume = volume.coerceIn(0f, 1f)
        if (!isMuted) {
            val volumeInt = (savedVolume * 100).toInt().coerceIn(0, 100)
            try {
                mpv?.setPropertyInt("volume", volumeInt)
            } catch (_: Throwable) {}
        }
    }

    override fun getVolume(): Float = savedVolume

    override fun setMuted(muted: Boolean) {
        isMuted = muted
        val volumeInt = if (muted) 0 else (savedVolume * 100).toInt().coerceIn(0, 100)
        try {
            mpv?.setPropertyInt("volume", volumeInt)
        } catch (_: Throwable) {}
    }

    override fun isMuted(): Boolean = isMuted

    override fun setVideoQuality(maxHeight: Int) {
        // libmpv auto selects stream quality or video-unscaled
        try {
            mpv?.setPropertyInt("ytdl-format-max-height", maxHeight)
        } catch (_: Throwable) {}
    }

    override fun isPlaying(): Boolean = isEnginePlaying

    override fun isActive(): Boolean = isEngineActive

    override fun getCurrentPosition(): Long = currentPositionMs

    override fun getDuration(): Long = currentDurationMs

    override fun isSeekable(): Boolean = currentDurationMs > 0L

    override fun getSubtitleView(): SubtitleView? = null

    override fun setListener(listener: VideoEngineListener?) {
        this.listener = listener
    }

    companion object {
        private const val TAG = "MpvVideoEngine"
    }
}
