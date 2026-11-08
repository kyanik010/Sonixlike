package com.example

import org.junit.Assert.assertEquals
import org.junit.Test

class ExternalAudioRetryPolicyTest {
    @Test fun retryDelayMatchesSprtxxxBackoffSchedule() {
        assertEquals(1000L, externalAudioRetryDelayMs(1))
        assertEquals(2000L, externalAudioRetryDelayMs(2))
        assertEquals(4000L, externalAudioRetryDelayMs(3))
        assertEquals(8000L, externalAudioRetryDelayMs(4))
        assertEquals(16000L, externalAudioRetryDelayMs(5))
        assertEquals(30000L, externalAudioRetryDelayMs(6))
        assertEquals(30000L, externalAudioRetryDelayMs(20))
    }

    @Test fun firstRetryDelayIsUsedForZeroOrNegativeAttempt() {
        assertEquals(1000L, externalAudioRetryDelayMs(0))
        assertEquals(1000L, externalAudioRetryDelayMs(-3))
    }
}
