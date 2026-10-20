package com.github.dtretyakov.pebble.run

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConnectionHintTest {

    @Test
    fun `a refused phone connection points at Developer Connection`() {
        val hint = ConnectionHint.hint(PebbleRunTarget.PHONE, "192.168.1.20", "[Errno 111] Connection refused\n")!!
        assertTrue("Developer Connection" in hint && "192.168.1.20" in hint, hint)
    }

    @Test
    fun `CloudPebble without a login says to log in`() {
        assertTrue("pebble login" in ConnectionHint.hint(PebbleRunTarget.CLOUDPEBBLE, "", "You must login first")!!)
    }

    @Test
    fun `an emulator whose screen port is taken says who may hold it`() {
        val output = "Couldn't launch emulator:\nqemu-pebble: -vnc :1: Failed to find an available port: Address already in use\n"
        val hint = ConnectionHint.hint(PebbleRunTarget.EMULATOR, "", output)!!
        assertTrue("5901" in hint && "WSL" in hint, hint)
    }

    @Test
    fun `an error that is not about connecting gets no hint`() {
        assertNull(ConnectionHint.hint(PebbleRunTarget.PHONE, "1.2.3.4", "Error: this pbw does not support basalt"))
    }
}
