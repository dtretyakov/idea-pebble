package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.awt.Point
import java.awt.Rectangle

class EmulatorScreenTest {

    @Test
    fun `the screen sits at the top left, except on basalt`() {
        assertEquals(Rectangle(0, 0, 200, 228), EmulatorScreen.of("emery", 208, 228))
        assertEquals(Rectangle(0, 0, 260, 260), EmulatorScreen.of("gabbro", 272, 260))
        assertEquals(Rectangle(2, 2, 144, 168), EmulatorScreen.of("basalt", 160, 172))
        assertEquals(Rectangle(0, 0, 50, 40), EmulatorScreen.of("future", 50, 40))
    }

    @Test
    fun `a touch is stretched over the framebuffer the way QEMU shrinks it back`() {
        val emery = EmulatorScreen.of("emery", 208, 228)
        assertEquals(Point(0, 0), EmulatorScreen.pointerFor(emery, 208, 228, 0, 0))
        assertEquals(Point(207, 227), EmulatorScreen.pointerFor(emery, 208, 228, 199, 227))
        assertEquals(Point(104, 114), EmulatorScreen.pointerFor(emery, 208, 228, 100, 114))
        assertEquals(Point(207, 0), EmulatorScreen.pointerFor(emery, 208, 228, 500, -3))
    }
}
