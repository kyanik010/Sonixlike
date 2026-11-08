package com.example.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.util.Log

class SonixAudioFocusManager(
    private val context: Context,
    private val onAudioFocusLost: (transient: Boolean) -> Unit,
    private val onAudioFocusGained: () -> Unit,
    private val onAudioDuck: (duck: Boolean) -> Unit = {}
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnGain = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d(TAG, "Audio focus changed: $focusChange (current hasFocus=$hasFocus)")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                hasFocus = false
                resumeOnGain = false
                onAudioFocusLost(false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                resumeOnGain = true
                onAudioFocusLost(true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                onAudioDuck(true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                val wasPausedForTransient = resumeOnGain
                hasFocus = true
                resumeOnGain = false
                onAudioDuck(false)
                if (wasPausedForTransient) {
                    onAudioFocusGained()
                }
            }
        }
    }

    fun requestPlaybackFocus(): Boolean {
        val am = audioManager ?: return true
        if (hasFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        Log.i(TAG, "requestPlaybackFocus result: $result (granted=$hasFocus)")
        return hasFocus
    }

    fun abandonPlaybackFocus() {
        val am = audioManager ?: return
        if (!hasFocus && audioFocusRequest == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusChangeListener)
        }
        hasFocus = false
        resumeOnGain = false
        Log.d(TAG, "abandonPlaybackFocus completed")
    }

    fun hasAudioFocus(): Boolean = hasFocus

    companion object {
        private const val TAG = "SONIXAudioFocus"
    }
}
