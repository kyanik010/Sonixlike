package com.example

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

internal data class SubtitleSearchResult(
    val fileId: Long,
    val name: String,
    val langName: String,
    val iso: String,
    val rating: Double,
    val downloads: Int,
    val downloadUrl: String? = null
)

/**
 * Full OpenSubtitles REST API v1 client ported directly from Lumen-App's lib/opensubtitles.dart.
 * Also supports Stremio subtitle addon endpoints and direct subtitle downloading with
 * GZIP/ZIP extraction and Arabic Windows-1256 encoding fallback.
 */
internal object OpenSubtitlesClient {
    private const val BASE_URL = "https://api.opensubtitles.com/api/v1"
    private const val DEFAULT_USER_AGENT = "Lumen v1.1"

    val supportedLanguages = listOf(
        "all" to "الكل",
        "ar" to "عربي (Arabic)",
        "en" to "English",
        "ur" to "Urdu (اردو)",
        "hi" to "Hindi (हिंदी)",
        "es" to "Spanish",
        "fr" to "French",
        "de" to "German",
        "pt" to "Portuguese",
        "it" to "Italian",
        "tr" to "Turkish",
        "ru" to "Russian",
        "fa" to "Persian (فارسی)"
    )

    fun languageName(code: String): String {
        return supportedLanguages.firstOrNull { it.first.equals(code, ignoreCase = true) }?.second
            ?: code.uppercase(Locale.ROOT)
    }

    /**
     * Cleans extraneous media tags from IPTV titles (e.g. "AR: Inception (2010) [1080p] مترجم")
     * so that search engines find the actual movie/series.
     */
    fun cleanSearchQuery(raw: String): String {
        var q = raw.trim()
        // Remove country prefixes like "AR: ", "|AR| ", "EN - ", "[FR] "
        q = q.replace(Regex("""^(?:\[[A-Za-z0-9_-]+\]|\|[A-Za-z0-9_-]+\||[A-Za-z]{2,3}\s*[:\-|])\s*"""), "")
        // Remove common IPTV markers
        q = q.replace(Regex("""(?i)\b(?:4k|uhd|fhd|hd|sd|1080p|720p|2160p|h264|h265|hevc|x264|x265|web-?dl|bluray|bdrip|dvdrip|rip|aac|mp3|dubbed|subbed|remux|hdr|dv)\b"""), "")
        // Remove Arabic quality / translation keywords
        q = q.replace(Regex("""(?:مترجم|مدبلج|نسخة|أصلية|حصريا|جودة|عالية|كاملة)"""), "")
        // Remove brackets and years inside brackets
        q = q.replace(Regex("""\[.*?\]|\(.*?\)|[\{|\}]"""), " ")
        // Normalize whitespace
        q = q.replace(Regex("""\s+"""), " ").trim()
        return if (q.isBlank()) raw.trim() else q
    }

    /**
     * Performs subtitle search using OpenSubtitles REST API or fallback endpoints.
     */
    fun search(query: String, lang: String = "ar", apiKey: String = BuildConfig.OPENSUBTITLES_API_KEY): List<SubtitleSearchResult> {
        val cleaned = cleanSearchQuery(query)
        if (cleaned.isBlank()) return emptyList()
        require(apiKey.isNotBlank()) {
            "مفتاح OpenSubtitles API غير مضبوط في إعدادات البناء"
        }

        val encodedQuery = URLEncoder.encode(cleaned, "UTF-8")
        val langParam = if (lang != "all") "&languages=$lang" else ""
        val targetUrl = "$BASE_URL/subtitles?query=$encodedQuery$langParam&order_by=download_count&order_direction=desc"
        val connection = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 15000
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("Api-Key", apiKey)
            setRequestProperty("Accept", "application/json")
        }

        val results = mutableListOf<SubtitleSearchResult>()
        try {
            val code = connection.responseCode
            if (code != 200) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?.take(300).orEmpty()
                throw IllegalStateException(
                    when (code) {
                        429 -> "وصل OpenSubtitles إلى حد طلبات البحث المسموح. حاول لاحقاً."
                        401, 403 -> "رفض OpenSubtitles مفتاح API أو صلاحياته (HTTP $code)."
                        else -> "فشل البحث في OpenSubtitles (HTTP $code)${if (detail.isBlank()) "" else ": $detail"}"
                    }
                )
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val json = JSONObject(body)
            val data = json.optJSONArray("data") ?: JSONArray()
            val seenFiles = mutableSetOf<Long>()

            for (i in 0 until data.length()) {
                val row = data.optJSONObject(i) ?: continue
                val attributes = row.optJSONObject("attributes") ?: continue
                val files = attributes.optJSONArray("files") ?: continue
                val rowLang = attributes.optString("language", lang)

                for (j in 0 until files.length()) {
                    val file = files.optJSONObject(j) ?: continue
                    val fileId = file.optLong("file_id", 0L)
                    if (fileId <= 0L || !seenFiles.add(fileId)) continue

                    results.add(
                        SubtitleSearchResult(
                            fileId = fileId,
                            name = file.optString("file_name", attributes.optString("release", "Subtitle")),
                            langName = languageName(rowLang),
                            iso = rowLang,
                            rating = attributes.optDouble("ratings", 0.0),
                            downloads = attributes.optInt("download_count", 0)
                        )
                    )
                }
            }
        } finally {
            connection.disconnect()
        }
        return results.sortedByDescending { it.downloads }.take(40)
    }

    /**
     * Downloads and decodes a subtitle payload, handling GZIP, ZIP, and multi-charset decoding.
     */
    fun downloadAndDecode(
        rawUrl: String,
        fileId: Long = 0L,
        apiKey: String = BuildConfig.OPENSUBTITLES_API_KEY
    ): Pair<String, String> { // Returns Pair<TextContent, Extension>
        var downloadLink = rawUrl

        // OpenSubtitles file IDs must be resolved through its authenticated API.
        if (fileId > 0L) {
            require(apiKey.isNotBlank()) {
                "مفتاح OpenSubtitles API غير مضبوط في إعدادات البناء"
            }
            val request = (URL("$BASE_URL/download").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
                setRequestProperty("Api-Key", apiKey)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                doOutput = true
            }
            try {
                request.outputStream.use {
                    it.write(JSONObject().put("file_id", fileId).toString().toByteArray(Charsets.UTF_8))
                }
                val code = request.responseCode
                if (code !in 200..299) {
                    val detail = request.errorStream?.bufferedReader()?.use { it.readText() }
                        ?.take(300).orEmpty()
                    throw IllegalStateException(
                        if (code == 429 || code == 406) {
                            "وصل OpenSubtitles إلى حد التنزيل المسموح. حاول لاحقاً."
                        } else {
                            "فشل طلب رابط تنزيل OpenSubtitles (HTTP $code)${if (detail.isBlank()) "" else ": $detail"}"
                        }
                    )
                }
                val responseBody = request.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                downloadLink = JSONObject(responseBody).optString("link", "")
            } finally {
                request.disconnect()
            }
            require(downloadLink.startsWith("https://") || downloadLink.startsWith("http://")) {
                "لم يُرجع OpenSubtitles رابط تنزيل صالحاً"
            }
        }
        require(downloadLink.startsWith("https://") || downloadLink.startsWith("http://")) {
            "رابط تنزيل الترجمة غير صالح"
        }

        val connection = (URL(downloadLink).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 25000
            useCaches = false
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty(
                "Accept",
                "text/plain,text/vtt,application/x-subrip,application/octet-stream,application/zip,application/gzip,*/*"
            )
            setRequestProperty("Accept-Encoding", "gzip, deflate")
            instanceFollowRedirects = true
        }
        try {
            val responseCode = connection.responseCode
            require(responseCode in 200..299) {
                if (responseCode == 429 || responseCode == 406) {
                    "وصل OpenSubtitles إلى حد التنزيل المسموح. حاول لاحقاً."
                } else {
                    "فشل تنزيل ملف الترجمة (HTTP $responseCode)"
                }
            }
            val rawBytes = connection.inputStream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 20 * 1024 * 1024) { "حجم ملف الترجمة يتجاوز 20MB" }
                    out.write(buffer, 0, count)
                }
                out.toByteArray()
            }
            val decoded = SubtitlePipeline.decodePayload(
                rawBytes = rawBytes,
                contentEncoding = connection.contentEncoding,
                sourceUrl = connection.url.toString(),
                contentDisposition = connection.getHeaderField("Content-Disposition")
            )
            return Pair(decoded.text, decoded.extension)
        } finally {
            connection.disconnect()
        }
    }

    private fun decodeSubtitleBytes(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        // UTF-16LE BOM
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        // Try UTF-8
        try {
            val utf8 = String(bytes, Charsets.UTF_8)
            // If it decoded and contains standard chars or Arabic, return
            if (!utf8.contains('\uFFFD')) {
                return utf8
            }
        } catch (_: Exception) {}

        // Fallback for Arabic Windows-1256 & ISO-8859-6
        for (charset in listOf("windows-1256", "ISO-8859-6")) {
            try {
                val decoded = String(bytes, Charset.forName(charset))
                if (decoded.any { it in '\u0600'..'\u06FF' }) {
                    return decoded
                }
            } catch (_: Exception) {}
        }

        return String(bytes, Charset.forName("windows-1252"))
    }
}
