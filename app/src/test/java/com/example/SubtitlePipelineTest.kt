package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SubtitlePipelineTest {
    @Test
    fun parsesSrtCuesWithoutBlankSeparators() {
        val cues = SubtitlePipeline.parse(
            "1\n00:00:01,000 --> 00:00:02,500\nHello\n2\n00:00:03,000 --> 00:00:04,000\nWorld",
            "srt"
        )
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(2_500L, cues[0].endMs)
        assertEquals("Hello", cues[0].text)
        assertEquals("World", cues[1].text)
    }

    @Test
    fun parsesVttAndAssFormats() {
        val vtt = SubtitlePipeline.parse(
            "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nCaption",
            "vtt"
        )
        val ass = SubtitlePipeline.parse(
            "[Script Info]\n[Events]\nFormat: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text\nDialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Arabic line",
            "ass"
        )
        assertEquals("Caption", vtt.single().text)
        assertEquals("Arabic line", ass.single().text)
    }

    @Test
    fun parsesTtmlDurationBasedCues() {
        val cues = SubtitlePipeline.parse(
            """<tt><body><div><p begin="00:00:01.000" dur="2s">First</p><p begin="3s" end="00:00:05.000">Second</p></div></body></tt>""",
            "ttml"
        )
        assertEquals(2, cues.size)
        assertEquals(1_000L, cues[0].startMs)
        assertEquals(3_000L, cues[0].endMs)
        assertEquals("First", cues[0].text)
        assertEquals(3_000L, cues[1].startMs)
        assertEquals(5_000L, cues[1].endMs)
    }

    @Test
    fun detectsAndDecodesGzipPayload() {
        val raw = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello".toByteArray()
        val compressed = ByteArrayOutputStream().also { output ->
            GZIPOutputStream(output).use { it.write(raw) }
        }.toByteArray()
        val decoded = SubtitlePipeline.decodePayload(compressed, null, "captions.vtt.gz")
        assertEquals("vtt", decoded.extension)
        assertTrue(decoded.text.startsWith("WEBVTT"))
    }

    @Test
    fun extractsSupportedFileFromZipPayload() {
        val archive = ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("not a subtitle".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("movie.srt"))
                zip.write("1\n00:00:01,000 --> 00:00:02,000\nHello".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val decoded = SubtitlePipeline.decodePayload(archive, null, "download.zip")
        assertEquals("movie.srt", decoded.fileName)
        assertEquals("srt", decoded.extension)
        assertEquals(1, SubtitlePipeline.parse(decoded.text, decoded.extension).size)
    }

    @Test
    fun appliesPositiveAndNegativeSubtitleDelay() {
        val source = "00:00:10,000 --> 00:00:12,000"
        assertEquals(
            "00:00:11,500 --> 00:00:13,500",
            SubtitlePipeline.applyDelay(source, "srt", 1_500L)
        )
        assertEquals(
            "00:00:08,000 --> 00:00:10,000",
            SubtitlePipeline.applyDelay(source, "srt", -2_000L)
        )
    }

    @Test
    fun rejectsHtmlAndEmptyPayloads() {
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SubtitlePipeline.decodePayload("<html>error</html>".toByteArray(), null, "subtitle.srt")
        }
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            SubtitlePipeline.decodePayload(byteArrayOf(), null, "subtitle.srt")
        }
    }

    @Test
    fun convertsCommonSubtitleTimeUnits() {
        assertEquals(3_600_000L, SubtitlePipeline.timeToMs("1h"))
        assertEquals(90_000L, SubtitlePipeline.timeToMs("1.5m"))
        assertEquals(1_250L, SubtitlePipeline.timeToMs("1.25s"))
        assertEquals(1_250L, SubtitlePipeline.timeToMs("00:00:01.250"))
    }
}