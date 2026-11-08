package com.example

import com.example.model.RadioStation
import org.json.JSONArray
import org.json.JSONObject

/**
 * Robust classification and matching engine for discovering ALWAN and beIN SPORTS
 * audio/commentary stations from raw Xtream Live streams and categories.
 * Inspired by Orbital IPTV's stream matching and normalisation architecture.
 */
object RadioStationMatcher {

    private val DIACRITICS = Regex("""[\u064B-\u065F\u0670]""")
    private val QUALITY_TAGS = Regex("""\b(hd|4k|fhd|sd|uhd|hevc|h265|\d{2}fps)\b""", RegexOption.IGNORE_CASE)
    private val NON_ALPHANUMERIC = Regex("""[^a-z0-9\u0600-\u06FF ]""")
    private val MULTI_SPACES = Regex("""\s+""")
    private val ALWAN_WORD_PATTERN = Regex("""\b(alwan|al wan)\b""")

    /**
     * Normalizes text by removing diacritics, unifying Arabic letter forms,
     * stripping quality tags, and cleaning punctuation and whitespace.
     */
    fun normalize(text: String?): String {
        if (text.isNullOrBlank()) return ""
        return text.lowercase()
            .replace(DIACRITICS, "")
            .replace(Regex("[أإآ]"), "ا")
            .replace('ة', 'ه')
            .replace('ى', 'ي')
            .replace("+", " plus ")
            .replace("&", " ")
            .replace(QUALITY_TAGS, " ")
            .replace(NON_ALPHANUMERIC, " ")
            .replace(MULTI_SPACES, " ")
            .trim()
    }

    /**
     * Determines whether a channel belongs to ALWAN, BEIN_SPORTS, or none.
     * @return "ALWAN", "BEIN_SPORTS", or null if not an eligible audio/commentary source.
     */
    fun classify(channelName: String, categoryName: String): String? {
        val normChannel = normalize(channelName)
        val normCategory = normalize(categoryName)
        val combined = "$normChannel $normCategory"

        if (isAlwan(normChannel, normCategory, combined)) {
            return "ALWAN"
        }

        if (isBein(normChannel, normCategory, combined)) {
            return "BEIN_SPORTS"
        }

        return null
    }

    private fun isAlwan(channel: String, category: String, combined: String): Boolean {
        val alwanArabic = listOf("الوان", "صوتيات الوان", "راديو الوان", "تعليق الوان", "قنوات الوان")
        if (alwanArabic.any { channel.contains(it) || category.contains(it) }) {
            return true
        }

        if (ALWAN_WORD_PATTERN.containsMatchIn(combined)) {
            return true
        }

        return false
    }

    private fun isBein(channel: String, category: String, combined: String): Boolean {
        val beinPatterns = listOf(
            "bein sports", "bein sport", "beinsports", "beinsport",
            "be in sports", "be in sport", "بي ان سبورت", "بي ان", "بين سبورت",
            "صوتيات بي ان", "راديو بي ان", "تعليق بي ان", "صوتيات بين", "تعليق بين",
            "bein audio", "bein radio", "bein commentary"
        )
        if (beinPatterns.any { channel.contains(it) || category.contains(it) }) {
            return true
        }

        if (Regex("""\b(bein|be in)\b""").containsMatchIn(combined)) {
            return true
        }

        return false
    }

    /**
     * Pure Kotlin implementation taking raw lists, callable in JVM tests and production alike.
     */
    fun discoverStations(
        categories: List<Pair<String, String>>,
        streams: List<Map<String, String>>
    ): List<RadioStation> {
        val categoryMap = categories.associate { it.first.trim() to it.second.trim() }
        val discovered = mutableListOf<RadioStation>()
        val seenStreamIds = mutableSetOf<String>()

        for (stream in streams) {
            val streamId = stream["stream_id"]?.trim().orEmpty()
            if (streamId.isEmpty() || streamId == "null" || streamId.toLongOrNull() == null) continue
            if (!seenStreamIds.add(streamId)) continue

            val name = stream["name"]?.trim().orEmpty()
            if (name.isEmpty()) continue

            val categoryId = stream["category_id"]?.trim().orEmpty()
            val categoryName = categoryMap[categoryId] ?: stream["category_name"]?.trim().orEmpty()

            val group = classify(name, categoryName) ?: continue

            val ext = stream["container_extension"]?.trim()?.lowercase()?.removePrefix(".")?.ifEmpty { "ts" } ?: "ts"
            val num = stream["num"]?.trim()?.toIntOrNull() ?: 0
            val logoUrl = stream["stream_icon"]?.trim().orEmpty()

            discovered.add(
                RadioStation(
                    id = streamId,
                    name = name,
                    categoryName = categoryName,
                    group = group,
                    containerExtension = ext,
                    number = num,
                    logoUrl = logoUrl
                )
            )
        }

        return discovered.sortedWith(
            compareBy<RadioStation> { if (it.group == "ALWAN") 0 else 1 }
                .thenBy { if (it.number > 0) it.number else Int.MAX_VALUE }
                .thenBy { extractChannelIndex(it.name) }
                .thenBy { it.name }
        )
    }

    /**
     * Discovers all matching ALWAN and beIN SPORTS stations from raw Xtream JSON data.
     */
    fun discoverStations(categoriesJson: JSONArray, streamsJson: JSONArray): List<RadioStation> {
        val cats = mutableListOf<Pair<String, String>>()
        for (i in 0 until categoriesJson.length()) {
            val cat = categoriesJson.optJSONObject(i) ?: continue
            val id = cat.optString("category_id").trim()
            val name = cat.optString("category_name").trim()
            if (id.isNotEmpty() && name.isNotEmpty()) {
                cats.add(id to name)
            }
        }

        val strms = mutableListOf<Map<String, String>>()
        for (i in 0 until streamsJson.length()) {
            val s = streamsJson.optJSONObject(i) ?: continue
            val map = mutableMapOf<String, String>()
            val keys = s.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = s.optString(key, "")
            }
            strms.add(map)
        }

        return discoverStations(cats, strms)
    }

    /**
     * Extracts numerical channel index (e.g. "ALWAN 2" -> 2, "beIN 10" -> 10).
     */
    private fun extractChannelIndex(name: String): Int {
        val match = Regex("""\b(\d{1,3})\b""").find(name)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: Int.MAX_VALUE
    }
}
