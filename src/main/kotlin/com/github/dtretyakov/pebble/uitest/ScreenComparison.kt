package com.github.dtretyakov.pebble.uitest

import java.awt.image.BufferedImage

/**
 * A screenshot against its reference, pixel by pixel. The watch draws without anti-aliasing
 * noise, so the same screen is the same pixels; a tolerance allows for a clock or an animation.
 */
object ScreenComparison {

    data class Difference(val pixels: Int, val total: Int, val diff: BufferedImage?) {
        val matches: Boolean get() = pixels == 0
    }

    /** How many pixels differ, and a picture of where: the reference greyed, differences red. */
    fun compare(expected: BufferedImage, actual: BufferedImage): Difference {
        if (expected.width != actual.width || expected.height != actual.height) {
            return Difference(expected.width * expected.height, expected.width * expected.height, null)
        }
        var pixels = 0
        val diff = BufferedImage(expected.width, expected.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                val a = expected.getRGB(x, y)
                val b = actual.getRGB(x, y)
                if (a != b) {
                    pixels++
                    diff.setRGB(x, y, 0xFF0000)
                } else {
                    val grey = ((a shr 16 and 0xFF) + (a shr 8 and 0xFF) + (a and 0xFF)) / 3
                    val faded = 128 + grey / 2
                    diff.setRGB(x, y, (faded shl 16) or (faded shl 8) or faded)
                }
            }
        }
        return Difference(pixels, expected.width * expected.height, diff)
    }
}
