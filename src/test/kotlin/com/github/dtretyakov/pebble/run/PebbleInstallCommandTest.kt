package com.github.dtretyakov.pebble.run

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PebbleInstallCommandTest {

    @Test
    fun `an emulator run asks for VNC instead of a window of its own`() {
        assertEquals(
            listOf("install", "--emulator", "gabbro", "--vnc", "--logs"),
            PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "gabbro", "", "", logs = true),
        )
    }

    @Test
    fun `a phone run names the phone`() {
        assertEquals(
            listOf("install", "--phone", "192.168.1.20"),
            PebbleInstallCommand.arguments(PebbleRunTarget.PHONE, "emery", " 192.168.1.20 ", "", logs = false),
        )
    }

    @Test
    fun `CloudPebble and adb take their own options`() {
        assertEquals(
            listOf("install", "--cloudpebble", "--logs"),
            PebbleInstallCommand.arguments(PebbleRunTarget.CLOUDPEBBLE, null, "", "", logs = true),
        )
        assertEquals(
            listOf("install", "--adb", "--logs"),
            PebbleInstallCommand.arguments(PebbleRunTarget.ADB, null, "", "", logs = true),
        )
        assertEquals(
            listOf("install", "--adb", "R58M"),
            PebbleInstallCommand.arguments(PebbleRunTarget.ADB, null, "", "R58M", logs = false),
        )
    }
}
