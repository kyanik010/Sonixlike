package com.example.playback

import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.SubtitleView

interface VideoEngineListener {
    fun onPlaybackBuffering()
    fun onPlaybackReady()
    fun onPlaybackEnded()
    fun onIsPlayingChanged(isPlaying: Boolean)
    fun onPlaybackError(message: String, isFatal: Boolean, canFallbackToBackup: Boolean = false)
    fun onVideoSizeChanged(width: Int, height: Int)
}

interface VideoEngine {
    val engineType: VideoEngineType

    fun initialize(container: ViewGroup, initialCornerRadiusPx: Float): View
    fun getView(): View?
    fun detachView()
    fun setCornerRadius(radiusPx: Float)
    fun play(url: String, headers: Map<String, String> = emptyMap(), streamType: String = "live")
    fun pause()
    fun resume()
    fun stop()
    fun release()
    fun seekTo(positionMs: Long)
    fun seekBy(offsetMs: Long)
    fun setVolume(volume: Float)
    fun getVolume(): Float
    fun setMuted(muted: Boolean)
    fun isMuted(): Boolean
    fun setVideoQuality(maxHeight: Int)
    fun isPlaying(): Boolean
    fun isActive(): Boolean
    fun getCurrentPosition(): Long
    fun getDuration(): Long
    fun isSeekable(): Boolean
    fun getSubtitleView(): SubtitleView?
    fun setListener(listener: VideoEngineListener?)
}
