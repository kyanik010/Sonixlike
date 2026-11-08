package com.example.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

// Kept public and top-level for test compatibility
fun externalAudioRetryDelayMs(attempt: Int): Long {
    val bounded = attempt.coerceAtLeast(1)
    val backoff = 1000L shl (bounded - 1).coerceAtMost(5)
    return backoff.coerceAtMost(30000L)
}

interface ExternalAudioListener {
    fun onExternalAudioBuffering(message: String? = null)
    fun onExternalAudioReady()
    fun onExternalAudioError(message: String)
    fun onExternalAudioStopped()
}

class ExternalAudioController(
    private val context: Context,
    private val userAgent: String = "SONIX-TV/1.0"
) {
    private var secondaryAudioPlayer: ExoPlayer? = null
    private var externalAudioActive = false
    private var externalAudioGeneration = 0L
    private var externalAudioRetryAttempt = 0
    private var externalAudioRetryRunnable: Runnable? = null
    private val externalAudioHandler = Handler(Looper.getMainLooper())

    private var externalAudioSourceUrl = ""
    private var externalAudioVolume = 1f
    private var externalAudioSyncOffsetMs = 0L
    private var appliedExternalAudioSyncOffsetMs = 0L

    private var listener: ExternalAudioListener? = null

    private val mediaAudioAttributes: AudioAttributes = AudioAttributes.Builder()
        .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
        .setUsage(C.USAGE_MEDIA)
        .build()

    fun setListener(listener: ExternalAudioListener?) {
        this.listener = listener
    }

    fun isExternalAudioActive(): Boolean = externalAudioActive

    fun attachExternalAudio(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) {
            detachExternalAudio(notifyListener = true, errorMessage = "رابط الصوت الخارجي فارغ")
            return
        }

        externalAudioRetryRunnable?.let(externalAudioHandler::removeCallbacks)
        externalAudioRetryRunnable = null
        externalAudioGeneration++
        externalAudioRetryAttempt = 0
        appliedExternalAudioSyncOffsetMs = 0L
        externalAudioSourceUrl = trimmed
        externalAudioActive = true

        val generation = externalAudioGeneration
        listener?.onExternalAudioBuffering(null)
        startAudioPlaybackAttempt(generation)
    }

    private fun startAudioPlaybackAttempt(generation: Long) {
        if (generation != externalAudioGeneration || !externalAudioActive) return

        releasePlayerOnly()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3000, 20000, 1000, 2000)
            .build()

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10000)
            .setReadTimeoutMs(15000)

        val mediaSourceFactory = DefaultMediaSourceFactory(context)
            .setDataSourceFactory(DefaultDataSource.Factory(context, http))

        // CRITICAL ARCHITECTURAL DESIGN:
        // handleAudioFocus is explicitly false.
        // Secondary audio player must NEVER request OS audio focus independently,
        // which prevents any internal ExoPlayer focus-loss competition that stopped video.
        val player = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(mediaAudioAttributes, false)
            .build().apply {
                trackSelectionParameters = trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
                volume = externalAudioVolume
            }

        secondaryAudioPlayer = player

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (generation != externalAudioGeneration || secondaryAudioPlayer !== player) return
                when (state) {
                    Player.STATE_BUFFERING -> {
                        listener?.onExternalAudioBuffering(null)
                    }
                    Player.STATE_READY -> {
                        externalAudioRetryRunnable?.let(externalAudioHandler::removeCallbacks)
                        externalAudioRetryRunnable = null
                        externalAudioRetryAttempt = 0
                        applySyncOffset()
                        listener?.onExternalAudioReady()
                    }
                    Player.STATE_ENDED -> {
                        scheduleRetry(generation, "انتهى بث القناة الصوتية")
                    }
                    Player.STATE_IDLE -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (generation != externalAudioGeneration || secondaryAudioPlayer !== player) return
                Log.w(TAG, "External audio error: ${error.errorCodeName}")
                if (error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS) {
                    abortAndRestore(generation, "تعذر الاتصال بمصدر الصوت الخارجي (${error.errorCodeName})")
                } else {
                    scheduleRetry(generation, error.errorCodeName)
                }
            }
        })

        try {
            val audioUri = Uri.parse(externalAudioSourceUrl)
            val extension = audioUri.lastPathSegment?.substringAfterLast('.', "")?.lowercase().orEmpty()
            val mimeType = when (extension) {
                "m3u8" -> MimeTypes.APPLICATION_M3U8
                "mpd" -> MimeTypes.APPLICATION_MPD
                else -> null
            }
            val mediaItem = MediaItem.Builder()
                .setUri(audioUri)
                .apply { if (mimeType != null) setMimeType(mimeType) }
                .build()

            player.setMediaItem(mediaItem)
            player.prepare()
            player.playWhenReady = true
        } catch (e: Exception) {
            Log.w(TAG, "Error initiating secondary audio playback", e)
            scheduleRetry(generation, e.message ?: "فشل بدء التشغيل")
        }
    }

    private fun scheduleRetry(generation: Long, reason: String) {
        if (generation != externalAudioGeneration || !externalAudioActive) return
        releasePlayerOnly()

        externalAudioRetryAttempt++
        if (externalAudioRetryAttempt > 3) {
            abortAndRestore(
                generation,
                "تعذر استعادة الصوت الخارجي بعد 3 محاولات؛ تمت استعادة صوت القناة الأساسية"
            )
            return
        }

        val delayMs = externalAudioRetryDelayMs(externalAudioRetryAttempt)
        Log.w(TAG, "External audio retry $externalAudioRetryAttempt/3 in ${delayMs}ms: $reason")
        listener?.onExternalAudioBuffering("جارٍ إعادة الاتصال بمصدر الصوت...")

        val retry = Runnable {
            externalAudioRetryRunnable = null
            if (generation == externalAudioGeneration && externalAudioActive) {
                startAudioPlaybackAttempt(generation)
            }
        }
        externalAudioRetryRunnable = retry
        externalAudioHandler.postDelayed(retry, delayMs)
    }

    private fun abortAndRestore(generation: Long, message: String) {
        if (generation != externalAudioGeneration && !externalAudioActive) return
        detachExternalAudio(notifyListener = false)
        listener?.onExternalAudioError(message)
    }

    fun setExternalAudioVolume(volume: Float) {
        externalAudioVolume = volume.coerceIn(0f, 1f)
        secondaryAudioPlayer?.volume = externalAudioVolume
    }

    fun setExternalAudioSyncOffset(offsetMs: Long) {
        externalAudioSyncOffsetMs = offsetMs.coerceIn(-10000L, 10000L)
        applySyncOffset()
    }

    private fun applySyncOffset() {
        val player = secondaryAudioPlayer ?: return
        val delta = externalAudioSyncOffsetMs - appliedExternalAudioSyncOffsetMs
        if (delta == 0L) return
        try {
            if (player.isCurrentMediaItemSeekable) {
                val current = player.currentPosition
                val target = (current - delta).coerceAtLeast(0L)
                player.seekTo(target)
                appliedExternalAudioSyncOffsetMs = externalAudioSyncOffsetMs
            } else {
                appliedExternalAudioSyncOffsetMs = externalAudioSyncOffsetMs
            }
        } catch (e: Exception) {
            Log.w(TAG, "Sync offset seek failed", e)
        }
    }

    fun pause() {
        secondaryAudioPlayer?.pause()
    }

    fun resume() {
        if (externalAudioActive) {
            secondaryAudioPlayer?.play()
        }
    }

    fun detachExternalAudio(notifyListener: Boolean = true, errorMessage: String? = null) {
        externalAudioRetryRunnable?.let(externalAudioHandler::removeCallbacks)
        externalAudioRetryRunnable = null
        externalAudioGeneration++
        externalAudioRetryAttempt = 0
        externalAudioSourceUrl = ""
        appliedExternalAudioSyncOffsetMs = 0L

        val wasActive = externalAudioActive
        externalAudioActive = false

        releasePlayerOnly()

        if (wasActive && notifyListener) {
            if (errorMessage != null) {
                listener?.onExternalAudioError(errorMessage)
            } else {
                listener?.onExternalAudioStopped()
            }
        }
    }

    private fun releasePlayerOnly() {
        secondaryAudioPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
            player.release()
        }
        secondaryAudioPlayer = null
    }

    fun release() {
        detachExternalAudio(notifyListener = false)
    }

    companion object {
        private const val TAG = "SONIXExternalAudio"
    }
}
