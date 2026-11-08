package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class XtreamBridgeTest {

    @Test
    fun normalizeServer_validHttpAndHttps() {
        assertEquals("http://myiptv.com:8080", XtreamBridge.normalizeServer("  http://myiptv.com:8080/  "))
        assertEquals("https://secure.stream.net", XtreamBridge.normalizeServer("https://secure.stream.net"))
    }

    @Test
    fun normalizeServer_rejectsInvalidScheme() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamBridge.normalizeServer("ftp://myiptv.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XtreamBridge.normalizeServer("myiptv.com")
        }
    }

    @Test
    fun normalizeServer_rejectsUserInfoInHost() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamBridge.normalizeServer("http://user:pass@myiptv.com")
        }
    }

    @Test
    fun normalizeServer_rejectsQueryParamsOrFragments() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamBridge.normalizeServer("http://myiptv.com:8080/path?action=live")
        }
        assertThrows(IllegalArgumentException::class.java) {
            XtreamBridge.normalizeServer("http://myiptv.com:8080/#live")
        }
    }

    @Test
    fun normalizeServer_rejectsInvalidPort() {
        assertThrows(IllegalArgumentException::class.java) {
            XtreamBridge.normalizeServer("http://myiptv.com:70000")
        }
    }
}
