package com.github.dtretyakov.pebble.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage

class ScreenViewsTest {

    private fun screen(width: Int, height: Int): BufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        createGraphics().apply { color = Color.WHITE; fillRect(0, 0, width, height) }
        setRGB(0, 0, Color.RED.rgb)
    }

    @Test
    fun `every platform's screen comes out at about the same size, in whole pixels`() {
        assertEquals(4, ScreenViews.scale(144, 168))
        assertEquals(3, ScreenViews.scale(200, 228))
        assertEquals(3, ScreenViews.scale(260, 260))
        assertEquals(4, ScreenViews.scale(180, 180))
    }

    @Test
    fun `a pixel becomes a square block, never a blur`() {
        val large = ScreenViews.enlarge(screen(144, 168))
        assertEquals(576, large.width)
        assertEquals(672, large.height)
        for (x in 0 until 4) for (y in 0 until 4) assertEquals(Color.RED.rgb, large.getRGB(x, y))
        assertEquals(Color.WHITE.rgb, large.getRGB(4, 0))
    }

    @Test
    fun `a comparison puts the panels side by side under their captions`() {
        val panels = listOf("reference" to screen(200, 228), "now" to screen(200, 228), "difference" to screen(200, 228))
        val image = ScreenViews.sideBySide(panels)
        assertTrue(image.width > 3 * 400 && image.height > 456, "${image.width}x${image.height}")
        assertEquals(Color.RED.rgb, image.getRGB(16, 16 + 28), "the first panel's first pixel, after the margin and caption")
    }
}
