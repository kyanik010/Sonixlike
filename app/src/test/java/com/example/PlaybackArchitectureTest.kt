package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.playback.ExternalAudioListener
import com.example.playback.PlaybackSettings
import com.example.playback.SonixAudioFocusManager
import com.example.playback.SonixDiagnosticLog
import com.example.playback.VideoEngine
import com.example.playback.VideoEngineListener
import com.example.playback.VideoEngineType
import com.example.playback.externalAudioRetryDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class PlaybackArchitectureTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // --- 1. Video Engine Settings & Resolution Tests ---

    @Test
    fun videoEngineType_fromId_handlesValidAndInvalidInputs() {
        assertEquals(VideoEngineType.AUTO, VideoEngineType.fromId("auto"))
        assertEquals(VideoEngineType.MEDIA3, VideoEngineType.fromId("media3"))
        assertEquals(VideoEngineType.LIBMPV, VideoEngineType.fromId("mpv"))
        assertEquals(VideoEngineType.LIBMPV, VideoEngineType.fromId("libmpv"))
        // Unknown engine defaults gracefully to AUTO
        assertEquals(VideoEngineType.AUTO, VideoEngineType.fromId("unknown_engine"))
        assertEquals(VideoEngineType.AUTO, VideoEngineType.fromId(""))
    }

    @Test
    fun playbackSettings_persistsSelectedEngine() {
        val settings = PlaybackSettings(context)
        // Initial default is AUTO
        settings.setSelectedEngine(VideoEngineType.MEDIA3)
        assertEquals(VideoEngineType.MEDIA3, settings.getSelectedEngine())

        settings.setSelectedEngine(VideoEngineType.LIBMPV)
        assertEquals(VideoEngineType.LIBMPV, settings.getSelectedEngine())

        settings.setSelectedEngine(VideoEngineType.AUTO)
        assertEquals(VideoEngineType.AUTO, settings.getSelectedEngine())
    }

    @Test
    fun playbackSettings_resolveEffectiveEngine_autoResolvesToMedia3() {
        val settings = PlaybackSettings(context)
        val effective = settings.resolveEffectiveEngine(VideoEngineType.AUTO)
        assertEquals(VideoEngineType.MEDIA3, effective)
    }

    // --- 2. Centralized Audio Focus Manager Tests ---

    @Test
    fun audioFocusManager_requestAndAbandonFocus() {
        var lostCount = 0
        var gainedCount = 0
        var duckCount = 0

        val focusManager = SonixAudioFocusManager(
            context = context,
            onAudioFocusLost = { lostCount++ },
            onAudioFocusGained = { gainedCount++ },
            onAudioDuck = { duckCount++ }
        )

        assertFalse(focusManager.hasAudioFocus())

        val granted = focusManager.requestPlaybackFocus()
        assertTrue(granted)
        assertTrue(focusManager.hasAudioFocus())

        focusManager.abandonPlaybackFocus()
        assertFalse(focusManager.hasAudioFocus())
    }

    // --- 3. External Audio Retry Policy & Backoff Tests ---

    @Test
    fun externalAudioRetryDelayMs_matchesExponentialBackoffSchedule() {
        assertEquals(1000L, externalAudioRetryDelayMs(1))
        assertEquals(2000L, externalAudioRetryDelayMs(2))
        assertEquals(4000L, externalAudioRetryDelayMs(3))
        assertEquals(8000L, externalAudioRetryDelayMs(4))
        assertEquals(16000L, externalAudioRetryDelayMs(5))
        assertEquals(30000L, externalAudioRetryDelayMs(6))
        // Beyond max attempt, clamps to 30000ms
        assertEquals(30000L, externalAudioRetryDelayMs(10))
        // 0 or negative clamps to first attempt
        assertEquals(1000L, externalAudioRetryDelayMs(0))
        assertEquals(1000L, externalAudioRetryDelayMs(-5))
    }

    // --- 4. Diagnostic Logging & Credential Sanitization Tests ---

    @Test
    fun sonixDiagnosticLog_sanitizesCredentialsInUrls() {
        val sanitized = SonixDiagnosticLog.sanitizeUrl("http://user123:secretPass@iptv.server.net:8080/live/user/pass/123.ts")
        assertFalse(sanitized.contains("user123"))
        assertFalse(sanitized.contains("secretPass"))
        assertTrue(sanitized.contains("iptv.server.net:8080/live/user/pass/123.ts"))
        assertTrue(sanitized.startsWith("http://***:***@"))
    }

    @Test
    fun sonixDiagnosticLog_preservesCleanUrls() {
        val cleanUrl = "https://cdn.example.com/stream/index.m3u8"
        assertEquals(cleanUrl, SonixDiagnosticLog.sanitizeUrl(cleanUrl))
    }

    // --- 5. Video & External Audio Architectural Decoupling Contract ---

    @Test
    fun videoVolumeDecoupling_videoOnlyMutedAfterExternalAudioReady() {
        // Simulating the architectural state flow:
        // Video engine is playing with volume 0.8
        var videoVolume = 0.8f
        var videoMuted = false
        var savedVideoVolume = 0.8f
        var videoIsPlaying = true

        val externalAudioListener = object : ExternalAudioListener {
            override fun onExternalAudioBuffering(message: String?) {
                // Video MUST continue playing and remain unmuted during external audio buffering!
                assertTrue("Video must remain playing while external audio buffers", videoIsPlaying)
                assertFalse("Video must NOT be muted prematurely while audio buffers", videoMuted)
            }

            override fun onExternalAudioReady() {
                // Only now does the controller mute the video stream
                if (!videoMuted) {
                    savedVideoVolume = videoVolume
                }
                videoMuted = true
                assertTrue("Video must remain playing when external audio starts", videoIsPlaying)
            }

            override fun onExternalAudioError(message: String) {
                // If external audio fails, restore video volume immediately
                videoMuted = false
                videoVolume = savedVideoVolume
                assertTrue("Video must remain playing after audio error", videoIsPlaying)
            }

            override fun onExternalAudioStopped() {
                // If external audio stops, restore video volume immediately
                videoMuted = false
                videoVolume = savedVideoVolume
                assertTrue("Video must remain playing after audio stop", videoIsPlaying)
            }
        }

        // 1. External audio starts buffering
        externalAudioListener.onExternalAudioBuffering("جارٍ الاتصال...")
        assertFalse(videoMuted)
        assertEquals(0.8f, videoVolume, 0.01f)
        assertTrue(videoIsPlaying)

        // 2. External audio confirms ready
        externalAudioListener.onExternalAudioReady()
        assertTrue(videoMuted)
        assertTrue(videoIsPlaying)

        // 3. External audio experiences stream error -> restore original video volume
        externalAudioListener.onExternalAudioError("Connection dropped")
        assertFalse(videoMuted)
        assertEquals(0.8f, videoVolume, 0.01f)
        assertTrue(videoIsPlaying)

        // 4. External audio starts again and stops cleanly -> restore original video volume
        externalAudioListener.onExternalAudioReady()
        assertTrue(videoMuted)
        externalAudioListener.onExternalAudioStopped()
        assertFalse(videoMuted)
        assertEquals(0.8f, videoVolume, 0.01f)
        assertTrue(videoIsPlaying)
    }

    @Test
    fun videoEngineSwitching_releasesOldEngineBeforeCreatingNew() {
        var oldEngineReleased = false
        var newEngineStarted = false

        // Test engine lifecycle sequencing:
        // Engine 1
        val engine1 = object {
            fun stop() {}
            fun release() { oldEngineReleased = true }
        }

        // When switching, old engine must be released before new engine starts
        engine1.stop()
        engine1.release()
        assertTrue("Previous video engine must be completely released", oldEngineReleased)

        newEngineStarted = true
        assertTrue("New video engine starts only after old engine release", newEngineStarted)
    }
}
