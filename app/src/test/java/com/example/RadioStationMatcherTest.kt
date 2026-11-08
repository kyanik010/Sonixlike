package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RadioStationMatcherTest {

    @Test
    fun normalize_cleansDiacriticsAndQualityTags() {
        val raw = "  أَلْوَانْ 1 (FHD 50fps) + HEVC  "
        val normalized = RadioStationMatcher.normalize(raw)
        assertEquals("الوان 1 plus", normalized)
    }

    @Test
    fun classify_identifiesAlwanStations() {
        assertEquals("ALWAN", RadioStationMatcher.classify("ALWAN 1 FHD", "VIP Channels"))
        assertEquals("ALWAN", RadioStationMatcher.classify("صوتيات ألوان 2", "Audio Commentary"))
        assertEquals("ALWAN", RadioStationMatcher.classify("قناة رياضية", "ALWAN SPORTS"))
        assertEquals("ALWAN", RadioStationMatcher.classify("Al Wan Audio 3", "Sports Feeds"))
        assertEquals("ALWAN", RadioStationMatcher.classify("راديو الوان مباشر", "قنوات الراديو"))
    }

    @Test
    fun classify_identifiesBeinStations() {
        assertEquals("BEIN_SPORTS", RadioStationMatcher.classify("beIN SPORTS 1 HD", "Sports"))
        assertEquals("BEIN_SPORTS", RadioStationMatcher.classify("بي ان سبورت 2", "قنوات رياضية"))
        assertEquals("BEIN_SPORTS", RadioStationMatcher.classify("صوتيات بي ان 1", "Audio Feeds"))
        assertEquals("BEIN_SPORTS", RadioStationMatcher.classify("beIN Audio 4", "Commentary"))
        assertEquals("BEIN_SPORTS", RadioStationMatcher.classify("تعليق بي ان سبورت", "الراديو الرياضي"))
        assertEquals("BEIN_SPORTS", RadioStationMatcher.classify("beIN SPORTS MAX 1", "beIN SPORTS"))
    }

    @Test
    fun classify_rejectsUnrelatedChannels() {
        assertNull(RadioStationMatcher.classify("MBC 1 HD", "Arabic General"))
        assertNull(RadioStationMatcher.classify("OSN Movies Action", "Cinema"))
        assertNull(RadioStationMatcher.classify("Sky Sports Premier League", "UK Sports"))
        assertNull(RadioStationMatcher.classify("Rotana Cinema", "Movies"))
    }

    @Test
    fun discoverStations_extractsAndSortsAccurately() {
        val categories = listOf(
            "10" to "صوتيات ألوان",
            "20" to "beIN SPORTS",
            "30" to "قنوات عامة"
        )

        val streams = listOf(
            mapOf("stream_id" to "100", "name" to "MBC Masr", "category_id" to "30"),
            mapOf("stream_id" to "202", "name" to "beIN SPORTS 2", "category_id" to "20", "num" to "2"),
            mapOf("stream_id" to "201", "name" to "beIN SPORTS 1", "category_id" to "20", "num" to "1"),
            mapOf("stream_id" to "102", "name" to "ALWAN 2", "category_id" to "10", "num" to "2"),
            mapOf("stream_id" to "101", "name" to "ALWAN 1", "category_id" to "10", "num" to "1"),
            mapOf("stream_id" to "101", "name" to "ALWAN 1 Duplicate", "category_id" to "10")
        )

        val discovered = RadioStationMatcher.discoverStations(categories, streams)

        assertEquals(4, discovered.size)

        // ALWAN stations come first, sorted by number
        assertEquals("101", discovered[0].id)
        assertEquals("ALWAN 1", discovered[0].name)
        assertEquals("ALWAN", discovered[0].group)

        assertEquals("102", discovered[1].id)
        assertEquals("ALWAN 2", discovered[1].name)
        assertEquals("ALWAN", discovered[1].group)

        // beIN stations come second, sorted by number
        assertEquals("201", discovered[2].id)
        assertEquals("beIN SPORTS 1", discovered[2].name)
        assertEquals("BEIN_SPORTS", discovered[2].group)

        assertEquals("202", discovered[3].id)
        assertEquals("beIN SPORTS 2", discovered[3].name)
        assertEquals("BEIN_SPORTS", discovered[3].group)
    }
}
