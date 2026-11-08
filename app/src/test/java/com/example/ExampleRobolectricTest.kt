package com.example

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ExampleRobolectricTest {

    @Test
    fun cssPreviewBoundsAreScaledByEffectivePixelRatio() {
        assertEquals(360, cssPixelsToAndroidPixels(100f, 3.6f))
        assertEquals(0, cssPixelsToAndroidPixels(0f, 2f))
    }

    @Test
    fun nativePlayerViewUsesTextureSurfaceAndTransparentShutter() {
        val layout = File("src/main/res/layout/native_player_view.xml")
        assertTrue("native PlayerView layout must exist", layout.exists())
        val xml = layout.readText()
        assertTrue(xml.contains("""app:surface_type="texture_view""""))
        assertTrue(xml.contains("""app:use_controller="false""""))
        assertTrue(xml.contains("""app:shutter_background_color="@android:color/transparent""""))
    }

    @Test
    fun verifyAppNameConstant() {
        val appName = "SONIX TV"
        assertEquals("SONIX TV", appName)
    }

    @Test
    fun verifyAssetFilesExist() {
        val assetsHtml = File("src/main/assets/sonix_tv.html")
        val assetsCss = File("src/main/assets/styles.css")
        val assetsJs = File("src/main/assets/app.js")
        assertTrue("sonix_tv.html asset must exist", assetsHtml.exists())
        assertTrue("styles.css asset must exist", assetsCss.exists())
        assertTrue("app.js asset must exist", assetsJs.exists())
        assertTrue("sonix_tv.html references styles.css", assetsHtml.readText().contains("styles.css"))
        assertTrue("sonix_tv.html references app.js", assetsHtml.readText().contains("app.js"))
    }

    @Test
    fun calculatePlayerLayoutParams_validBounds_returnsLayoutParams() {
        val rect = PreviewLayoutCalculator.calculate(
            left = 100f,
            top = 50f,
            width = 400f,
            height = 225f,
            pixelRatio = 1.5f,
            rootWidth = 1920,
            rootHeight = 1080
        )

        assertNotNull(rect)
        assertEquals(600, rect!!.width)
        assertEquals(338, rect.height)
        assertEquals(150, rect.left)
        assertEquals(75, rect.top)
    }

    @Test
    fun calculatePlayerLayoutParams_subpixelOverflow_safelyClamped() {
        val rect = PreviewLayoutCalculator.calculate(
            left = -1f,
            top = 0f,
            width = 500f,
            height = 300f,
            pixelRatio = 1f,
            rootWidth = 1000,
            rootHeight = 800
        )

        assertNotNull(rect)
        assertEquals(0, rect!!.left)
        assertEquals(499, rect.width)
    }

    @Test
    fun calculatePlayerLayoutParams_invalidCoordinates_returnsNull() {
        assertNull(PreviewLayoutCalculator.calculate(Float.NaN, 0f, 100f, 100f, 1f, 1920, 1080))
        assertNull(PreviewLayoutCalculator.calculate(10f, 10f, 0f, 100f, 1f, 1920, 1080))
        assertNull(PreviewLayoutCalculator.calculate(10f, 10f, 100f, -5f, 1f, 1920, 1080))
        assertNull(PreviewLayoutCalculator.calculate(10f, 10f, 100f, 100f, 0.1f, 1920, 1080))
        assertNull(PreviewLayoutCalculator.calculate(10f, 10f, 100f, 100f, 15f, 1920, 1080))
        assertNull(PreviewLayoutCalculator.calculate(2500f, 2500f, 100f, 100f, 1f, 1920, 1080))
    }
}
