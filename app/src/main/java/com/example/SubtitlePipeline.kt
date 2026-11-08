package com.example

import androidx.media3.common.MimeTypes
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

internal data class DecodedSubtitle(
    val text: String,
    val extension: String,
    val fileName: String
)

/**
 * Pure subtitle transport/decoding/parsing helpers shared by the Android pipeline and JVM tests.
 * Network I/O stays in MainActivity; this object validates and normalizes the bytes it receives.
 */
internal object SubtitlePipeline {
    private const val MAX_SUBTITLE_BYTES = 20 * 1024 * 1024
    private val supportedExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml", "txt", "sub")
    private val preferredSubtitleExtensions = setOf("srt", "vtt", "ass", "ssa", "ttml", "xml", "sub")

    fun decodePayload(
        rawBytes: ByteArray,
        contentEncoding: String?,
        sourceUrl: String,
        contentDisposition: String? = null
    ): DecodedSubtitle {
        require(rawBytes.isNotEmpty()) { "ملف الترجمة فارغ" }
        require(rawBytes.size <= MAX_SUBTITLE_BYTES) { "ملف الترجمة أكبر من 20MB" }

        val dispositionName = fileNameFromDisposition(contentDisposition)
        val urlName = sourceUrl.substringBefore('#').substringBefore('?').substringAfterLast('/')
        var fileName = dispositionName?.takeIf { it.isNotBlank() } ?: urlName
        var bytes = rawBytes
        val encoding = contentEncoding.orEmpty().lowercase(Locale.ROOT).trim()

        if (encoding.split(',').any { it.trim() == "gzip" } || isGzip(bytes)) {
            bytes = readBounded(GZIPInputStream(ByteArrayInputStream(bytes)))
            if (fileName.endsWith(".gz", ignoreCase = true)) fileName = fileName.dropLast(3)
        } else if (encoding.split(',').any { it.trim() == "deflate" }) {
            bytes = readBounded(InflaterInputStream(ByteArrayInputStream(bytes)))
        }

        if (isZip(bytes)) {
            val entry = extractSubtitleFromZip(bytes)
            bytes = entry.second
            fileName = entry.first
        }

        require(bytes.isNotEmpty()) { "ملف الترجمة بعد فك الضغط فارغ" }
        require(bytes.size <= MAX_SUBTITLE_BYTES) { "ملف الترجمة بعد فك الضغط أكبر من 20MB" }

        val text = decodeText(bytes)
            .removePrefix("\uFEFF")
            .replace("\u0000", "")
            .trimStart()
        require(text.isNotBlank()) { "تعذر فك ترميز ملف الترجمة" }

        val first = text.trimStart().take(256).lowercase(Locale.ROOT)
        require(!first.startsWith("<!doctype html") && !first.startsWith("<html") &&
            !first.startsWith("{\"") && !first.startsWith("{\"error\"")) {
            "مصدر الترجمة أعاد صفحة ويب أو استجابة JSON بدلاً من ملف ترجمة"
        }

        val extension = normalizeExtension(fileName, text)
        return DecodedSubtitle(text, extension, fileName.ifBlank { "subtitle.$extension" })
    }

    fun normalizeExtension(rawNameOrUrl: String, text: String = ""): String {
        val clean = rawNameOrUrl.substringBefore('?').substringBefore('#').trim().lowercase(Locale.ROOT)
        val withoutCompression = clean.removeSuffix(".gz").removeSuffix(".zip")
        val ext = withoutCompression.substringAfterLast('.', "")
        if (ext in supportedExtensions) return ext
        val sample = text.trimStart()
        return when {
            sample.startsWith("WEBVTT", ignoreCase = true) -> "vtt"
            sample.contains("[Script Info]", ignoreCase = true) -> "ass"
            sample.contains("<tt", ignoreCase = true) -> "ttml"
            Regex("""(?m)^\s*\{\d+}\{\d+}""").containsMatchIn(sample) -> "sub"
            else -> "srt"
        }
    }

    fun parse(text: String, extension: String): List<TimedSubtitleCue> {
        val source = text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val result = mutableListOf<TimedSubtitleCue>()

        fun add(start: String, end: String, rawText: String) {
            val startMs = timeToMs(start) ?: return
            val endMs = timeToMs(end) ?: return
            val clean = cleanCueText(rawText)
            if (clean.isNotBlank() && endMs > startMs) {
                result += TimedSubtitleCue(startMs, endMs, clean)
            }
        }

        when (extension.lowercase(Locale.ROOT)) {
            "ass", "ssa" -> {
                var columns = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
                source.lineSequence().forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("Format:", ignoreCase = true)) {
                        columns = trimmed.substringAfter(':').split(',').map { it.trim().lowercase(Locale.ROOT) }
                        return@forEach
                    }
                    if (!trimmed.startsWith("Dialogue:", ignoreCase = true)) return@forEach
                    val startIndex = columns.indexOf("start")
                    val endIndex = columns.indexOf("end")
                    val textIndex = columns.indexOf("text")
                    if (startIndex < 0 || endIndex < 0 || textIndex < 0) return@forEach
                    val fields = trimmed.substringAfter(':').split(',', limit = columns.size)
                    if (fields.size >= columns.size) add(fields[startIndex], fields[endIndex], fields[textIndex])
                }
            }
            "ttml", "xml" -> {
                val paragraph = Regex("(?is)<p\\b([^>]*)>(.*?)</p>")
                val attribute = Regex("""([\w:-]+)\s*=\s*["']([^"']+)["']""")
                paragraph.findAll(source).forEach { match ->
                    val attrs = attribute.findAll(match.groupValues[1])
                        .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    val begin = attrs["begin"] ?: return@forEach
                    val end = attrs["end"] ?: attrs["dur"]?.let { duration ->
                        val startMs = timeToMs(begin) ?: return@let null
                        val durationMs = timeToMs(duration) ?: return@let null
                        val totalMs = startMs + durationMs
                        String.format(
                            Locale.US,
                            "%02d:%02d:%02d.%03d",
                            totalMs / 3_600_000L,
                            (totalMs / 60_000L) % 60L,
                            (totalMs / 1_000L) % 60L,
                            totalMs % 1_000L
                        )
                    } ?: return@forEach
                    add(begin, end, match.groupValues[2])
                }
            }
            "sub" -> {
                // MicroDVD: {startFrame}{endFrame}subtitle text.
                val lines = source.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
                var fps = 25.0
                val frameCue = Regex("""^\{(\d+)}\{(\d+)}(.*)$""")
                lines.forEach { line ->
                    val match = frameCue.matchEntire(line) ?: return@forEach
                    val first = match.groupValues[1].toLongOrNull() ?: return@forEach
                    val last = match.groupValues[2].toLongOrNull() ?: return@forEach
                    val body = match.groupValues[3]
                    if (first == 1L && last == 1L && body.toDoubleOrNull() != null) {
                        fps = body.toDouble().coerceIn(1.0, 120.0)
                    } else if (last > first) {
                        val startMs = (first * 1000.0 / fps).toLong()
                        val endMs = (last * 1000.0 / fps).toLong()
                        val clean = cleanCueText(body.replace("|", "\n"))
                        if (clean.isNotBlank() && endMs > startMs) result += TimedSubtitleCue(startMs, endMs, clean)
                    }
                }
            }
            else -> {
                // Parse timestamped SRT/VTT cues across the whole document. Some providers
                // omit blank separators, so splitting on empty lines silently loses cues.
                val timestamp = Regex(
                    """^\s*((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3})\s*-->\s*((?:\d{1,2}:)?\d{2}:\d{2}[,.]\d{1,3})(?:\s+.*)?$"""
                )
                val lines = source.lines()
                var index = 0
                while (index < lines.size) {
                    val match = timestamp.matchEntire(lines[index])
                    if (match == null) {
                        index++
                        continue
                    }
                    val textLines = mutableListOf<String>()
                    index++
                    while (index < lines.size && timestamp.matchEntire(lines[index]) == null) {
                        val line = lines[index]
                        if (line.isNotBlank() && !Regex("""^\s*\d+\s*$""").matches(line)) textLines += line
                        index++
                    }
                    add(match.groupValues[1], match.groupValues[2], textLines.joinToString("\n"))
                }
            }
        }
        return result.sortedBy { it.startMs }
    }

    fun timeToMs(raw: String): Long? {
        val value = raw.trim().lowercase(Locale.ROOT).replace(',', '.')
        return try {
            val scalar = when {
                value.endsWith("ms") -> value.dropLast(2).toDouble()
                value.endsWith("s") -> value.dropLast(1).toDouble() * 1000.0
                value.endsWith("m") -> value.dropLast(1).toDouble() * 60_000.0
                value.endsWith("h") -> value.dropLast(1).toDouble() * 3_600_000.0
                else -> null
            }
            val milliseconds = if (scalar != null) scalar else {
                val parts = value.split(':')
                val seconds = when (parts.size) {
                    3 -> parts[0].toLong() * 3600.0 + parts[1].toLong() * 60.0 + parts[2].toDouble()
                    2 -> parts[0].toLong() * 60.0 + parts[1].toDouble()
                    1 -> parts[0].toDouble() * 1000.0
                    else -> return null
                }
                seconds * 1000.0
            }
            milliseconds.toLong().takeIf { it >= 0L }
        } catch (_: Exception) { null }
    }

    private fun cleanCueText(raw: String): String =
        raw.replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'")
            .replace("\\N", "\n")
            .replace(Regex("\\{[^}]*}"), "")
            .trim()

    private fun decodeText(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { }

        for (charsetName in listOf("windows-1256", "ISO-8859-6")) {
            try {
                val decoded = String(bytes, Charset.forName(charsetName))
                if (decoded.any { it in '\u0600'..'\u06FF' }) return decoded
            } catch (_: Exception) { }
        }
        return String(bytes, Charset.forName("windows-1252"))
    }

    private fun fileNameFromDisposition(value: String?): String? {
        if (value.isNullOrBlank()) return null
        val extended = Regex("""filename\*\s*=\s*UTF-8''([^;]+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        val plain = Regex("""filename\s*=\s*["']?([^;"']+)""", RegexOption.IGNORE_CASE)
            .find(value)?.groupValues?.getOrNull(1)
        val raw = extended ?: plain ?: return null
        return try { URLDecoder.decode(raw.trim(), "UTF-8") } catch (_: Exception) { raw.trim() }
    }

    private fun isGzip(bytes: ByteArray): Boolean =
        bytes.size >= 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte()

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4b.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    private fun extractSubtitleFromZip(bytes: ByteArray): Pair<String, ByteArray> {
        var fallback: Pair<String, ByteArray>? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.substringAfterLast('/')
                val data = readBounded(zip)
                zip.closeEntry()
                if (data.isEmpty()) continue
                val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                if (ext in preferredSubtitleExtensions) return name to data
                if (fallback == null && ext in supportedExtensions) fallback = name to data
            }
        }
        return fallback ?: throw IllegalArgumentException("أرشيف ZIP لا يحتوي على ملف ترجمة")
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= MAX_SUBTITLE_BYTES) { "ملف الترجمة بعد فك الضغط أكبر من 20MB" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    /**
     * Shifts timestamps in SRT or WebVTT subtitles by offsetMs.
     * Positive values delay subtitles; negative values advance them.
     */
    fun applyDelay(sourceText: String, extension: String, offsetMs: Long): String {
        if (offsetMs == 0L) return sourceText
        val ext = extension.lowercase(Locale.ROOT)
        if (ext != "srt" && ext != "vtt") return sourceText
        val timestampRegex = Regex("""(?<![\d:])(?:(\d{1,2}):)?(\d{2}):(\d{2})([,.])(\d{1,3})(?!\d)""")
        return timestampRegex.replace(sourceText) { match ->
            val hoursStr = match.groupValues[1]
            val minutesStr = match.groupValues[2]
            val secondsStr = match.groupValues[3]
            val sep = match.groupValues[4]
            val millisStr = match.groupValues[5].padEnd(3, '0').take(3)

            val hours = if (hoursStr.isNotEmpty()) hoursStr.toLongOrNull() ?: 0L else 0L
            val minutes = minutesStr.toLongOrNull() ?: 0L
            val seconds = secondsStr.toLongOrNull() ?: 0L
            val millis = millisStr.toLongOrNull() ?: 0L

            val originalMs = (hours * 3600L + minutes * 60L + seconds) * 1000L + millis
            val adjustedMs = (originalMs + offsetMs).coerceAtLeast(0L)

            val adjHours = adjustedMs / 3_600_000L
            val adjMinutes = (adjustedMs / 60_000L) % 60L
            val adjSeconds = (adjustedMs / 1_000L) % 60L
            val adjMillis = adjustedMs % 1_000L

            if (hoursStr.isNotEmpty()) {
                String.format(
                    Locale.US,
                    "%02d:%02d:%02d%s%03d",
                    adjHours, adjMinutes, adjSeconds, sep, adjMillis
                )
            } else {
                String.format(
                    Locale.US,
                    "%02d:%02d%s%03d",
                    adjMinutes + adjHours * 60L, adjSeconds, sep, adjMillis
                )
            }
        }
    }

    fun getMimeTypeForExtension(extension: String): String = when (extension.lowercase(Locale.ROOT).removePrefix(".")) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        "ttml", "xml" -> MimeTypes.APPLICATION_TTML
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    fun detectLanguage(label: String, fileName: String = ""): String {
        val text = "${label.lowercase(Locale.ROOT)} ${fileName.lowercase(Locale.ROOT)}"
        return when {
            text.contains("arabic") || text.contains("العربية") || text.contains("عربي") || text.contains(".ar.") || text.endsWith(".ar") -> "ar"
            text.contains("english") || text.contains("انجليزي") || text.contains(".en.") || text.endsWith(".en") -> "en"
            text.contains("french") || text.contains("français") || text.contains(".fr.") || text.endsWith(".fr") -> "fr"
            text.contains("spanish") || text.contains("español") || text.contains(".es.") || text.endsWith(".es") -> "es"
            text.contains("german") || text.contains("deutsch") || text.contains(".de.") || text.endsWith(".de") -> "de"
            text.contains("italian") || text.contains("italiano") || text.contains(".it.") || text.endsWith(".it") -> "it"
            text.contains("portuguese") || text.contains("português") || text.contains(".pt.") || text.endsWith(".pt") -> "pt"
            text.contains("russian") || text.contains("русский") || text.contains(".ru.") || text.endsWith(".ru") -> "ru"
            text.contains("turkish") || text.contains("türkçe") || text.contains(".tr.") || text.endsWith(".tr") -> "tr"
            text.contains("persian") || text.contains("farsi") || text.contains("فارسی") || text.contains(".fa.") || text.endsWith(".fa") -> "fa"
            else -> "und"
        }
    }
}
