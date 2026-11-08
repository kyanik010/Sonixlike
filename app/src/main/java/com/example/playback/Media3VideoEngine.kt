package com.example.playback

import android.content.Context
import android.graphics.Color
import android.graphics.Outline
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.example.LiveReconnectDataSource
import com.example.Media3PlaybackMode
import com.example.Media3PlaybackPolicy
import com.example.R
import kotlin.math.roundToInt

@UnstableApi
class Media3VideoEngine(
    private val context: Context,
    private val userAgent: String = "SONIX-TV/1.0"
) : VideoEngine {

    override val engineType: VideoEngineType = VideoEngineType.MEDIA3

    private var exoPlayer: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var listener: VideoEngineListener? = null
    private var isEngineActive = false
    private var isEnginePlaying = false
    private var isMuted = false
    private var savedVolume = 1f
    private var activeStreamType = "live"
    private var activeUrl = ""
    private var cornerRadiusPx = 0f

    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
        .setUsage(C.USAGE_MEDIA)
        .build()

    override fun initialize(container: ViewGroup, initialCornerRadiusPx: Float): View {
        cornerRadiusPx = initialCornerRadiusPx
        val view = playerView ?: (
            LayoutInflater.from(context)
                .inflate(R.layout.native_player_view, container, false) as PlayerView
            ).also { playerView = it }

        view.apply {
            setUseController(false)
            setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT)
            setKeepContentOnPlayerReset(true)
            setShutterBackgroundColor(Color.TRANSPARENT)
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = true
            clipToPadding = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, cornerRadiusPx.coerceAtLeast(0f))
                }
            }
        }

        if (view.parent !== container) {
            (view.parent as? ViewGroup)?.removeView(view)
            container.addView(view, 0)
        }
        return view
    }

    override fun getView(): View? = playerView

    override fun detachView() {
        playerView?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
            view.player = null
        }
        playerView = null
    }

    override fun setCornerRadius(radiusPx: Float) {
        cornerRadiusPx = radiusPx
        playerView?.invalidateOutline()
    }

    override fun play(url: String, headers: Map<String, String>, streamType: String) {
        activeUrl = url
        activeStreamType = streamType
        isEngineActive = true
        val isLiveStream = streamType.equals("live", ignoreCase = true)

        val player = exoPlayer ?: createExoPlayer(isLiveStream).also {
            exoPlayer = it
            playerView?.player = it
        }

        player.volume = if (isMuted) 0f else savedVolume

        val mediaUri = Uri.parse(url)
        val extension = mediaUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val mimeType = when {
            extension == "m3u8" || url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            extension == "mpd" || url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }

        val mediaItem = MediaItem.Builder()
            .setUri(mediaUri)
            .apply { if (mimeType != null) setMimeType(mimeType) }
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    private fun createExoPlayer(isLiveStream: Boolean): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)

        val buffers = Media3PlaybackPolicy.buffers(isLiveStream, Media3PlaybackMode.BALANCED)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffers.minBufferMs,
                buffers.maxBufferMs,
                buffers.bufferForPlaybackMs,
                buffers.bufferForPlaybackAfterRebufferMs
            )
            .setBackBuffer(buffers.backBufferMs, buffers.retainBackBufferFromKeyframe)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val networkFactory = if (isLiveStream) {
            LiveReconnectDataSource.Factory(
                upstreamFactory = http,
                reconnectDelayMs = Media3PlaybackPolicy.eofReconnectDelayMs(Media3PlaybackMode.BALANCED),
                shouldReconnectAtEof = { isLiveStream && activeUrl.endsWith(".ts", ignoreCase = true) }
            )
        } else {
            http
        }

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, networkFactory))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(3))

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        // CRITICAL ARCHITECTURAL DESIGN:
        // handleAudioFocus is explicitly false.
        // Audio focus is managed centrally by SonixAudioFocusManager to prevent
        // internal focus-loss competition between video and external audio!
        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(mediaAudioAttributes, false)
            .build().apply {
                addListener(PlayerEventListener())
            }
    }

    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_BUFFERING -> listener?.onPlaybackBuffering()
                Player.STATE_READY -> listener?.onPlaybackReady()
                Player.STATE_ENDED -> listener?.onPlaybackEnded()
                Player.STATE_IDLE -> Unit
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            isEnginePlaying = isPlaying
            listener?.onIsPlayingChanged(isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e("Media3VideoEngine", "Playback error: ${error.errorCodeName}", error)
            listener?.onPlaybackError(
                error.localizedMessage ?: error.errorCodeName,
                isFatal = true,
                canFallbackToBackup = true
            )
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                listener?.onVideoSizeChanged(videoSize.width, videoSize.height)
            }
        }
    }

    override fun pause() {
        exoPlayer?.pause()
    }

    override fun resume() {
        exoPlayer?.play()
    }

    override fun stop() {
        isEngineActive = false
        isEnginePlaying = false
        exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
    }

    override fun release() {
        stop()
        exoPlayer?.release()
        exoPlayer = null
        detachView()
    }

    override fun seekTo(positionMs: Long) {
        val player = exoPlayer ?: return
        if (player.isCurrentMediaItemSeekable) {
            val target = positionMs.coerceAtLeast(0L)
            val duration = player.duration
            player.seekTo(if (duration > 0L) target.coerceAtMost(duration) else target)
        }
    }

    override fun seekBy(offsetMs: Long) {
        val player = exoPlayer ?: return
        if (player.isCurrentMediaItemSeekable) {
            val current = player.currentPosition
            seekTo(current + offsetMs)
        }
    }

    override fun setVolume(volume: Float) {
        savedVolume = volume.coerceIn(0f, 1f)
        if (!isMuted) {
            exoPlayer?.volume = savedVolume
        }
    }

    override fun getVolume(): Float = savedVolume

    override fun setMuted(muted: Boolean) {
        isMuted = muted
        exoPlayer?.volume = if (muted) 0f else savedVolume
    }

    override fun isMuted(): Boolean = isMuted

    override fun setVideoQuality(maxHeight: Int) {
        val player = exoPlayer ?: return
        val height = maxHeight.coerceIn(360, 2160)
        val width = (height * 16f / 9f).roundToInt()
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setMaxVideoSize(width, height)
            .build()
    }

    override fun isPlaying(): Boolean = isEnginePlaying

    override fun isActive(): Boolean = isEngineActive

    override fun getCurrentPosition(): Long = exoPlayer?.currentPosition ?: 0L

    override fun getDuration(): Long = exoPlayer?.duration ?: 0L

    override fun isSeekable(): Boolean = exoPlayer?.isCurrentMediaItemSeekable ?: false

    override fun getSubtitleView(): SubtitleView? = playerView?.subtitleView

    override fun setListener(listener: VideoEngineListener?) {
        this.listener = listener
    }
}
