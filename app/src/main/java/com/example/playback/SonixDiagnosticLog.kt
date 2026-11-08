package com.example.playback

import android.net.Uri
import android.util.Log

object SonixDiagnosticLog {
    private const val TAG = "SONIXDiagnostic"

    fun logEngineSwitch(fromEngine: VideoEngineType, toEngine: VideoEngineType, reason: String) {
        Log.i(TAG, "[ENGINE_SWITCH] from=${fromEngine.name} to=${toEngine.name} reason='$reason'")
    }

    fun logPlaybackSession(
        engine: VideoEngineType,
        url: String,
        streamType: String,
        videoWidth: Int,
        videoHeight: Int,
        frameRate: Float,
        decoderName: String?,
        playbackState: String,
        playWhenReady: Boolean,
        isPlaying: Boolean,
        suppressionReason: String,
        audioFocusGranted: Boolean,
        externalAudioActive: Boolean,
        bufferingDurationMs: Long,
        droppedFrames: Long
    ) {
        val safeUrl = sanitizeUrl(url)
        Log.i(
            TAG,
            "[PLAYBACK_STATE] engine=${engine.name} streamType=$streamType url=$safeUrl " +
                "resolution=${videoWidth}x${videoHeight} fps=$frameRate decoder=${decoderName ?: "default"} " +
                "state=$playbackState playWhenReady=$playWhenReady isPlaying=$isPlaying " +
                "suppression=$suppressionReason audioFocus=$audioFocusGranted " +
                "extAudioActive=$externalAudioActive bufferDuration=${bufferingDurationMs}ms droppedFrames=$droppedFrames"
        )
    }

    fun logStopReason(
        engine: VideoEngineType,
        category: StopCategory,
        details: String
    ) {
        Log.w(TAG, "[PLAYBACK_STOP] engine=${engine.name} category=${category.name} details='$details'")
    }

    fun logExternalAudioEvent(
        state: String,
        stationName: String,
        url: String,
        generation: Long,
        attempt: Int,
        error: String? = null
    ) {
        val safeUrl = sanitizeUrl(url)
        val errPart = if (!error.isNullOrBlank()) " error='$error'" else ""
        Log.i(
            TAG,
            "[EXTERNAL_AUDIO] state=$state station='$stationName' url=$safeUrl generation=$generation attempt=$attempt$errPart"
        )
    }

    fun sanitizeUrl(rawUrl: String): String {
        return try {
            val uri = Uri.parse(rawUrl)
            val builder = uri.buildUpon()
            // Strip user credentials and mask with ***:***@
            val userInfo = uri.userInfo
            val host = uri.host
            if (host != null) {
                val port = if (uri.port != -1) ":${uri.port}" else ""
                val auth = if (!userInfo.isNullOrEmpty()) "***:***@$host$port" else "$host$port"
                builder.encodedAuthority(auth)
            }
            // Strip sensitive query params (password, token, key, secret)
            val queryNames = uri.queryParameterNames
            if (queryNames.isNotEmpty()) {
                builder.clearQuery()
                for (name in queryNames) {
                    val lower = name.lowercase()
                    if (lower.contains("pass") || lower.contains("token") || lower.contains("key") || lower.contains("secret")) {
                        builder.appendQueryParameter(name, "[REDACTED]")
                    } else {
                        builder.appendQueryParameter(name, uri.getQueryParameter(name))
                    }
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            "[SANITIZED_URL]"
        }
    }

    enum class StopCategory {
        AUDIO_FOCUS,
        DECODER_ERROR,
        NETWORK_FAILURE,
        BUFFERING_TIMEOUT,
        LIFECYCLE,
        SURFACE_DESTROYED,
        USER_NAVIGATION,
        INTERNAL_ERROR
    }
}
