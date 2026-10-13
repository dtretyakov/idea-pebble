package com.github.dtretyakov.pebble.project

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CompileDatabaseTest {

    @Test
    fun `the platform is read from the define every entry carries`() {
        val database = """[{"arguments": ["arm-none-eabi-gcc", "-DRELEASE", "-DPBL_PLATFORM_GABBRO", "-DPBL_COLOR"]}]"""
        assertEquals("gabbro", CompileDatabase.platformIn(database))
    }

    @Test
    fun `a database from elsewhere has no platform`() {
        assertNull(CompileDatabase.platformIn("""[{"arguments": ["cc", "-c", "main.c"]}]"""))
    }
}
