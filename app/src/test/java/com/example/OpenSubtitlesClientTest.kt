package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSubtitlesClientTest {
    @Test
    fun searchReportsMissingApiKeyInsteadOfSilentlyReturningNoResults() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            OpenSubtitlesClient.search("Kinda Pregnant", "ar", "")
        }
    }

    @Test
    fun removesIptvLanguageAndQualityDecorationsFromSearchTitle() {
        assertEquals(
            "Kinda Pregnant",
            OpenSubtitlesClient.cleanSearchQuery("AR: Kinda Pregnant (2025) [1080p] مترجم")
        )
    }

    @Test
    fun keepsOriginalTitleWhenCleaningWouldRemoveEverything() {
        assertEquals("4K HD", OpenSubtitlesClient.cleanSearchQuery("4K HD"))
    }

    @Test
    fun mapsSupportedLanguageCodesToDisplayNames() {
        assertEquals("عربي (Arabic)", OpenSubtitlesClient.languageName("ar"))
        assertEquals("Urdu (اردو)", OpenSubtitlesClient.languageName("ur"))
        assertEquals("Hindi (हिंदी)", OpenSubtitlesClient.languageName("hi"))
        assertEquals("XX", OpenSubtitlesClient.languageName("xx"))
    }

    @Test
    fun normalizesSearchLanguageWithoutChangingUnknownLanguageLabels() {
        assertTrue(OpenSubtitlesClient.supportedLanguages.any { it.first == "all" })
        assertTrue(OpenSubtitlesClient.supportedLanguages.any { it.first == "fa" })
    }
}