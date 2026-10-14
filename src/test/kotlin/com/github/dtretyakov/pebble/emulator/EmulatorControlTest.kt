package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmulatorControlTest {

    @Test
    fun `every command keeps the emulator's VNC, or pebble-tool restarts it without`() {
        assertEquals(
            listOf("emu-battery", "--percent", "20", "--charging", "--emulator", "gabbro", "--vnc"),
            EmulatorControl.Battery(20, charging = true).commandLine("gabbro"),
        )
        assertEquals(
            listOf("emu-bt-connection", "--connected", "no", "--emulator", "emery", "--vnc"),
            EmulatorControl.Bluetooth(false).commandLine("emery"),
        )
    }

    @Test
    fun `each control speaks pebble-tool's arguments`() {
        assertEquals(listOf("emu-time-format", "--format", "24h"), EmulatorControl.TimeFormat(true).commandLine("emery").take(3))
        assertEquals(listOf("emu-set-time", "1791930929"), EmulatorControl.SetTime(1791930929).commandLine("emery").take(2))
        assertEquals(listOf("emu-tap", "--direction", "z-"), EmulatorControl.Tap("z-").commandLine("emery").take(3))
        assertEquals(listOf("emu-accel", "tilt-left"), EmulatorControl.Tilt("tilt-left").commandLine("emery").take(2))
        assertEquals(listOf("emu-compass", "--heading", "90", "--calibrated"), EmulatorControl.Compass(90).commandLine("emery").take(4))
        assertEquals(listOf("emu-set-timeline-quick-view", "on"), EmulatorControl.TimelineQuickView(true).commandLine("emery").take(2))
        assertEquals(listOf("emu-set-content-size", "large"), EmulatorControl.ContentSize("large").commandLine("emery").take(2))
    }
}
