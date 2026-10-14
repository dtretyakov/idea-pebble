package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.EmulatorControl
import com.github.dtretyakov.pebble.emulator.EmulatorState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Every control against the running emulator: pebble-tool accepts it, and the emulator on screen
 * is the same one afterwards — not restarted without its VNC server.
 */
class EmulatorControlLiveTest {

    @Test
    fun `every control is accepted and leaves the emulator running`() {
        val root = LiveSdk.fixture("fixture-c")
        check(LiveSdk.run(root, "build").exitCode == 0)
        val install = LiveSdk.install(root, "--emulator", "emery", "--vnc")
        assertEquals(0, install.exitCode, install.text)
        val before = EmulatorState.withScreen()!!.qemuPid

        listOf(
            EmulatorControl.Battery(20, charging = false),
            EmulatorControl.Battery(80, charging = true),
            EmulatorControl.Bluetooth(false),
            EmulatorControl.Bluetooth(true),
            EmulatorControl.TimeFormat(true),
            EmulatorControl.SetTime(System.currentTimeMillis() / 1000),
            EmulatorControl.Tap("x+"),
            EmulatorControl.Tilt("tilt-left"),
            EmulatorControl.Tilt("none"),
            EmulatorControl.Compass(90),
            EmulatorControl.TimelineQuickView(false),
            EmulatorControl.ContentSize("medium"),
        ).forEach { control ->
            val output = LiveSdk.run(root, *control.commandLine("emery").toTypedArray())
            assertEquals(0, output.exitCode, "$control: ${output.text}")
        }

        assertEquals(before, EmulatorState.withScreen()?.qemuPid, "the emulator was restarted")
    }
}
