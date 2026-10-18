package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.EmulatorState
import com.github.dtretyakov.pebble.emulator.EmulatorWarmUp
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import java.net.Socket

/**
 * A run on an emulator whose watch has gone silent: QEMU and pypkjs up, pypkjs listening, the
 * firmware answering nothing — made here by stopping the emulated CPU from QEMU's monitor. The
 * warm-up restarts it, and the install that follows goes through.
 */
class EmulatorWarmUpLiveTest {

    @Test
    fun `a silent emulator is restarted, and the app installs on the new one`() {
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c").toRealPath()
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val before = EmulatorState.running().first { it.platform == "emery" && it.vnc }
        Socket("127.0.0.1", before.monitorPort!!).use { monitor ->
            monitor.getOutputStream().write("stop\n".toByteArray())
            monitor.getOutputStream().flush()
            Thread.sleep(500)
        }

        EmulatorWarmUp.ensureUp(tool, root, "emery", sdkVersion = null, indicator = null)

        val after = EmulatorState.running().firstOrNull { it.platform == "emery" && it.vnc }
        assertNotNull(after, "an emulator is up again")
        assertNotEquals(before.qemuPid, after!!.qemuPid, "a new one, not the silent one")
        assertEquals(0, LiveSdk.run(root, "install", "--emulator", "emery", "--vnc").exitCode)
    }
}
