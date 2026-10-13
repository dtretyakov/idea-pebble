package com.github.dtretyakov.pebble.run

/** Where a run installs the app — each one a pebble-tool connection option. */
enum class PebbleRunTarget(val display: String) {
    /** The SDK's emulator for the chosen platform, shown in the IDE through VNC. */
    EMULATOR("Emulator"),

    /** A phone running the Pebble app with Developer Connection on, reached over the LAN. */
    PHONE("Phone (Developer Connection)"),

    /** The phone again, through the cloud — after `pebble login`. */
    CLOUDPEBBLE("Phone (CloudPebble)"),

    /** An Android phone over adb, with its developer connection forwarded. */
    ADB("Android phone over adb");

    override fun toString(): String = display

    companion object {
        fun of(name: String?): PebbleRunTarget = entries.firstOrNull { it.name == name } ?: EMULATOR
    }
}
