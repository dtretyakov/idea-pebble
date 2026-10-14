package com.github.dtretyakov.pebble.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class GdbSessionTest {

    @Test
    fun `install then gdb, both keeping the emulator's VNC`() {
        val (install, gdb) = GdbSession.arguments("emery")
        assertEquals(listOf("install", "--emulator", "emery", "--vnc"), install)
        assertEquals(listOf("gdb", "--emulator", "emery", "--vnc"), gdb)
    }

    @Test
    fun `words are quoted for sh, quotes and spaces included`() {
        assertEquals("""'/home/a b/pebble' 'it'\''s'""", GdbSession.shell(listOf("/home/a b/pebble", "it's")))
    }
}
