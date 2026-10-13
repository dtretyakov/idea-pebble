package com.github.dtretyakov.pebble.run

/**
 * The arguments of `pebble install` for one run — kept apart from the run state so the mapping
 * from a configuration to pebble-tool's options can be tested as text.
 */
object PebbleInstallCommand {

    fun arguments(target: PebbleRunTarget, platform: String?, phoneAddress: String, adbSerial: String, logs: Boolean): List<String> =
        buildList {
            add("install")
            when (target) {
                PebbleRunTarget.EMULATOR -> {
                    add("--emulator")
                    add(requireNotNull(platform) { "an emulator run needs a platform" })
                    // No SDL window of its own: the screen is shown in the IDE, over VNC.
                    add("--vnc")
                }
                PebbleRunTarget.PHONE -> {
                    add("--phone")
                    add(phoneAddress.trim())
                }
                PebbleRunTarget.CLOUDPEBBLE -> add("--cloudpebble")
                PebbleRunTarget.ADB -> {
                    add("--adb")
                    if (adbSerial.isNotBlank()) add(adbSerial.trim())
                }
            }
            if (logs) add("--logs")
        }
}
