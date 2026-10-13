package com.github.dtretyakov.pebble.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ActivePlatformTest {

    @Test
    fun `the developer's choice wins while the project targets it`() {
        assertEquals("gabbro", ActivePlatform.resolve("gabbro", listOf("emery", "gabbro")))
    }

    @Test
    fun `emery by default, as pebble-tool's compile-commands does`() {
        assertEquals("emery", ActivePlatform.resolve("", listOf("basalt", "emery", "gabbro")))
    }

    @Test
    fun `a choice the project dropped falls back`() {
        assertEquals("basalt", ActivePlatform.resolve("emery", listOf("basalt", "chalk")))
    }

    @Test
    fun `nothing to build for, no platform`() {
        assertNull(ActivePlatform.resolve("emery", emptyList()))
    }

    @Test
    fun `no targetPlatforms means every platform the SDK has`() {
        assertEquals(listOf("aplite", "basalt"), ActivePlatform.candidates(emptyList(), listOf("aplite", "basalt")))
        assertEquals(listOf("emery"), ActivePlatform.candidates(listOf("emery"), listOf("aplite", "basalt")))
        assertEquals(7, ActivePlatform.candidates(emptyList(), emptyList()).size)
    }
}
