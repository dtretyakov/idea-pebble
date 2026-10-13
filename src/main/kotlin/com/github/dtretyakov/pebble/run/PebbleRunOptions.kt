package com.github.dtretyakov.pebble.run

import com.intellij.execution.configurations.RunConfigurationOptions

/** What one Pebble run configuration remembers. */
class PebbleRunOptions : RunConfigurationOptions() {

    private val targetOption = string(PebbleRunTarget.EMULATOR.name).provideDelegate(this, "target")

    /** Empty means "the platform chosen beside the Run button". */
    private val platformOption = string("").provideDelegate(this, "platform")
    private val phoneAddressOption = string("").provideDelegate(this, "phoneAddress")
    private val adbSerialOption = string("").provideDelegate(this, "adbSerial")
    private val logsOption = property(true).provideDelegate(this, "logs")
    private val debugBuildOption = property(false).provideDelegate(this, "debugBuild")

    var target: PebbleRunTarget
        get() = PebbleRunTarget.of(targetOption.getValue(this))
        set(value) = targetOption.setValue(this, value.name)

    var platform: String
        get() = platformOption.getValue(this).orEmpty()
        set(value) = platformOption.setValue(this, value)

    /** The phone's address as the Pebble app shows it under Developer Connection, `IP[:port]`. */
    var phoneAddress: String
        get() = phoneAddressOption.getValue(this).orEmpty()
        set(value) = phoneAddressOption.setValue(this, value)

    /** Which adb device, when more than one is attached. Empty means the only one. */
    var adbSerial: String
        get() = adbSerialOption.getValue(this).orEmpty()
        set(value) = adbSerialOption.setValue(this, value)

    /** Keep the run going after installing, streaming the app's logs to the console. */
    var logs: Boolean
        get() = logsOption.getValue(this)
        set(value) = logsOption.setValue(this, value)

    /** Build with `--debug`: `-O0 -DPBL_DEBUG` for C, and xsbug for Alloy. */
    var debugBuild: Boolean
        get() = debugBuildOption.getValue(this)
        set(value) = debugBuildOption.setValue(this, value)
}
