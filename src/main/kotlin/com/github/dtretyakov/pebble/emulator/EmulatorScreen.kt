package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.sdk.PebblePlatform
import java.awt.Point
import java.awt.Rectangle

/**
 * Where the watch's screen is inside the emulator's framebuffer.
 *
 * QEMU's framebuffer is wider than the screen — rounded up to a multiple of 16 pixels, so emery's
 * 200×228 arrives as 208×228 and gabbro's 260×260 as 272×260 — with the screen at the top left
 * and black beside it. basalt is the exception: 160×172, with the 144×168 screen two pixels in.
 * Measured against SDK 4.33.1 on every platform; see docs/pebble-sdk-notes.md.
 */
object EmulatorScreen {

    /**
     * Where to point in the framebuffer to touch the screen at ([x], [y]).
     *
     * QEMU stretches its absolute pointer over the whole framebuffer and the watch maps that onto
     * the touch panel, so on emery a pointer at x 100 of 208 touches x 96 of 200. The inverse of
     * that stretch puts the touch where it was asked for: measured to within a pixel on emery.
     */
    fun pointerFor(screen: Rectangle, framebufferWidth: Int, framebufferHeight: Int, x: Int, y: Int): Point =
        Point(
            stretch(x.coerceIn(0, screen.width - 1), screen.width, framebufferWidth),
            stretch(y.coerceIn(0, screen.height - 1), screen.height, framebufferHeight),
        )

    private fun stretch(value: Int, from: Int, to: Int): Int =
        if (from <= 1) 0 else Math.round(value.toDouble() * (to - 1) / (from - 1)).toInt()

    fun of(platform: String, framebufferWidth: Int, framebufferHeight: Int): Rectangle {
        val known = PebblePlatform.of(platform)
            ?: return Rectangle(0, 0, framebufferWidth, framebufferHeight)
        val offset = if (known == PebblePlatform.BASALT) 2 else 0
        return Rectangle(
            offset,
            offset,
            known.width.coerceAtMost(framebufferWidth - offset),
            known.height.coerceAtMost(framebufferHeight - offset),
        )
    }
}
