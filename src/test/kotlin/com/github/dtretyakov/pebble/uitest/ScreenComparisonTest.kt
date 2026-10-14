package com.github.dtretyakov.pebble.uitest

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class ScreenComparisonTest {

    private fun image(width: Int = 3, height: Int = 2, red: Int = 0) = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB).apply {
        for (x in 0 until red) setRGB(x, 0, 0xFF0000)
    }

    @Test
    fun `the same pixels match, and each differing one is counted and marked`() {
        assertTrue(ScreenComparison.compare(image(), image()).matches)
        val difference = ScreenComparison.compare(image(), image(red = 2))
        assertEquals(2, difference.pixels)
        assertEquals(6, difference.total)
        assertEquals(0xFF0000, difference.diff!!.getRGB(1, 0) and 0xFFFFFF)
        assertEquals(0x808080, difference.diff!!.getRGB(2, 1) and 0xFFFFFF)
    }

    @Test
    fun `a screen of another size does not match at all`() {
        val difference = ScreenComparison.compare(image(), image(width = 4))
        assertFalse(difference.matches)
        assertNull(difference.diff)
    }
}
