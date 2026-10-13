package com.github.dtretyakov.pebble.sdk

/**
 * The three systems the SDK is laid out differently on.
 *
 * Read from `os.name` rather than the platform's `SystemInfo`, so everything under `sdk/` stays
 * free of IDE API and can be tested as plain functions of a directory tree.
 */
enum class HostOs {
    MAC,
    LINUX,

    /** The SDK does not run here natively; pebble-tool supports Windows through WSL only. */
    WINDOWS;

    companion object {
        fun current(): HostOs = of(System.getProperty("os.name").orEmpty())

        fun of(osName: String): HostOs {
            val name = osName.lowercase()
            return when {
                name.startsWith("mac") || name.startsWith("darwin") -> MAC
                name.startsWith("windows") -> WINDOWS
                else -> LINUX
            }
        }
    }
}
