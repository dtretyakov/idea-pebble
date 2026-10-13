package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.sdk.PebblePlatform
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
