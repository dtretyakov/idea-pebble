package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunTarget

/**
 * What the emulator can be told about the world it is in — pebble-tool's `emu-*` commands, each
 * aimed at the running emulator.
 *
 * Every command names the emulator the way it was started, `--emulator P --vnc`: pebble-tool
 * restarts an emulator whose VNC setting differs from the command's, so leaving `--vnc` out would
 * kill the one on screen and open a window of its own instead.
 */
sealed class EmulatorControl(private val command: String) {

    protected abstract fun arguments(): List<String>

    fun commandLine(platform: String): List<String> =
        listOf(command) + arguments() +
            PebbleInstallCommand.connection(PebbleRunTarget.EMULATOR, platform, phoneAddress = "", adbSerial = "")

    data class Battery(val percent: Int, val charging: Boolean) : EmulatorControl("emu-battery") {
        override fun arguments() = listOfNotNull("--percent", percent.toString(), "--charging".takeIf { charging })
    }

    data class Bluetooth(val connected: Boolean) : EmulatorControl("emu-bt-connection") {
        override fun arguments() = listOf("--connected", if (connected) "yes" else "no")
    }

    data class TimeFormat(val twentyFourHour: Boolean) : EmulatorControl("emu-time-format") {
        override fun arguments() = listOf("--format", if (twentyFourHour) "24h" else "12h")
    }

    /** The watch's clock set to [epochSeconds] — this computer's time, when it has drifted. */
    data class SetTime(val epochSeconds: Long) : EmulatorControl("emu-set-time") {
        override fun arguments() = listOf(epochSeconds.toString())
    }

    /** A tap, as the accelerometer feels it, along one axis. */
    data class Tap(val direction: String) : EmulatorControl("emu-tap") {
        override fun arguments() = listOf("--direction", direction)

        companion object {
            val DIRECTIONS = listOf("x+", "x-", "y+", "y-", "z+", "z-")
        }
    }

    /** The watch held at an angle, or `none`. */
    data class Tilt(val motion: String) : EmulatorControl("emu-accel") {
        override fun arguments() = listOf(motion)

        companion object {
            val MOTIONS = listOf("tilt-left", "tilt-right", "tilt-forward", "tilt-back", "none")
        }
    }

    data class Compass(val heading: Int) : EmulatorControl("emu-compass") {
        override fun arguments() = listOf("--heading", heading.toString(), "--calibrated")
    }

    data class TimelineQuickView(val visible: Boolean) : EmulatorControl("emu-set-timeline-quick-view") {
        override fun arguments() = listOf(if (visible) "on" else "off")
    }

    data class ContentSize(val size: String) : EmulatorControl("emu-set-content-size") {
        override fun arguments() = listOf(size)

        companion object {
            val SIZES = listOf("small", "medium", "large", "x-large")
        }
    }
}
