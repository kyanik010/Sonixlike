package com.example

internal data class Media3BufferDurations(
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
    val backBufferMs: Int,
    val retainBackBufferFromKeyframe: Boolean
)

internal enum class Media3PlaybackMode {
    BALANCED,
    STABLE,
    LOW_LATENCY;

    companion object {
        fun from(raw: String?): Media3PlaybackMode = when (raw) {
            "stable" -> STABLE
            "lowLatency" -> LOW_LATENCY
            else -> BALANCED
        }
    }
}

internal enum class SeekFeedbackSide { LEFT, CENTER, RIGHT }

/**
 * Buffer and playback policy ported directly from Lumen-App.
 * Provides tuned buffer durations, live stream reconnect delays, and seek rules.
 */
internal object Media3PlaybackPolicy {
    private val onDemand = Media3BufferDurations(
        minBufferMs = 45_000,
        maxBufferMs = 120_000,
        bufferForPlaybackMs = 10_000,
        bufferForPlaybackAfterRebufferMs = 22_000,
        backBufferMs = 15_000,
        retainBackBufferFromKeyframe = true
    )

    fun buffers(
        isLive: Boolean,
        mode: Media3PlaybackMode = Media3PlaybackMode.BALANCED
    ): Media3BufferDurations {
        if (!isLive) return onDemand
        return when (mode) {
            Media3PlaybackMode.BALANCED -> Media3BufferDurations(
                minBufferMs = 30_000,
                maxBufferMs = 60_000,
                bufferForPlaybackMs = 2_000,
                bufferForPlaybackAfterRebufferMs = 4_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false
            )
            Media3PlaybackMode.STABLE -> Media3BufferDurations(
                minBufferMs = 45_000,
                maxBufferMs = 90_000,
                bufferForPlaybackMs = 6_000,
                bufferForPlaybackAfterRebufferMs = 8_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false
            )
            Media3PlaybackMode.LOW_LATENCY -> Media3BufferDurations(
                minBufferMs = 8_000,
                maxBufferMs = 20_000,
                bufferForPlaybackMs = 500,
                bufferForPlaybackAfterRebufferMs = 1_000,
                backBufferMs = 0,
                retainBackBufferFromKeyframe = false
            )
        }
    }

    fun eofReconnectDelayMs(mode: Media3PlaybackMode): Long = when (mode) {
        Media3PlaybackMode.LOW_LATENCY -> 0L
        Media3PlaybackMode.BALANCED -> 75L
        Media3PlaybackMode.STABLE -> 150L
    }

    fun bufferingLabel(isLive: Boolean, reconnecting: Boolean): String = when {
        isLive && reconnecting -> "Reconnecting to live stream…"
        isLive -> "Connecting to live stream…"
        reconnecting -> "Recovering video…"
        else -> "Buffering…"
    }

    fun seekFeedbackSide(offsetMs: Long, isTelevision: Boolean): SeekFeedbackSide =
        if (!isTelevision) {
            SeekFeedbackSide.CENTER
        } else if (offsetMs < 0L) {
            SeekFeedbackSide.LEFT
        } else {
            SeekFeedbackSide.RIGHT
        }

    fun heldSeekDistanceMs(repeatCount: Int): Long {
        if (repeatCount <= 0) return 10_000L
        return (1L + (repeatCount - 1) / 8L) * 60_000L
    }

    fun shouldTryAlternate(
        isLive: Boolean,
        usingAlternateSource: Boolean,
        currentUrl: String,
        alternateUrl: String
    ): Boolean =
        isLive &&
            !usingAlternateSource &&
            alternateUrl.isNotBlank() &&
            alternateUrl != currentUrl

    fun resumePositionMs(
        isLive: Boolean,
        savedPositionMs: Long,
        sessionPositionMs: Long
    ): Long = if (isLive) {
        0L
    } else {
        maxOf(savedPositionMs, sessionPositionMs).coerceAtLeast(0L)
    }
}
