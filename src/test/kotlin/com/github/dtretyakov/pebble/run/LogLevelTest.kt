package com.github.dtretyakov.pebble.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Against `pebble install --logs` output captured on a pseudo-terminal with pebble-tool 5.0.40. */
class LogLevelTest {

    private val esc = "\u001b"

    @Test
    fun `every level is read from its colours`() {
        assertEquals(LogLevel.ERROR, LogLevel.ofLine("$esc[41m$esc[1m$esc[37m[06:03:16] main.c:3> an error$esc[0m\r"))
        assertEquals(LogLevel.WARNING, LogLevel.ofLine("$esc[1m$esc[31m[06:03:16] main.c:4> a warning$esc[0m\r"))
        assertEquals(LogLevel.INFO, LogLevel.ofLine("[06:03:16] main.c:5> an info\r"))
        assertEquals(LogLevel.DEBUG, LogLevel.ofLine("$esc[35m[06:03:16] main.c:6> a debug$esc[0m\r"))
        assertEquals(LogLevel.VERBOSE, LogLevel.ofLine("$esc[36m[06:03:16] main.c:7> a verbose$esc[0m\r"))
        assertEquals(LogLevel.INFO, LogLevel.ofLine("[06:03:16] pkjs> ./src/pkjs/index.js:3:0 Hello\r"))
    }

    @Test
    fun `what is not a log line has no level and is always shown`() {
        assertNull(LogLevel.ofLine("Installing app...\r"))
        assertTrue(LogLevel.passes("App install succeeded.\r", LogLevel.ERROR))
    }

    @Test
    fun `lines below the minimum are left out`() {
        assertFalse(LogLevel.passes("$esc[35m[06:03:16] main.c:6> a debug$esc[0m", LogLevel.INFO))
        assertTrue(LogLevel.passes("$esc[1m$esc[31m[06:03:16] main.c:4> a warning$esc[0m", LogLevel.INFO))
        assertFalse(LogLevel.passes("[06:03:16] main.c:5> an info", LogLevel.WARNING))
    }
}
