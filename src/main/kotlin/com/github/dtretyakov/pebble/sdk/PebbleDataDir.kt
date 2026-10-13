package com.github.dtretyakov.pebble.sdk

import java.nio.file.Files
import java.nio.file.Path

/**
 * The directory pebble-tool keeps everything in: installed SDKs, their toolchains, emulator state.
 *
 * The same rule as pebble-tool's own `get_persist_dir()`, which is the only definition there is:
 *
 * - macOS: `~/Library/Application Support/Pebble SDK`
 * - Linux: `~/.pebble-sdk` when it already exists — installations from before the move to the XDG
 *   layout — and `$XDG_DATA_HOME/pebble-sdk` (`~/.local/share/pebble-sdk`) otherwise.
 *
 * Windows has none: the SDK lives inside a WSL distribution, under that distribution's Linux home.
 */
object PebbleDataDir {

    fun locate(
        os: HostOs = HostOs.current(),
        home: Path = Path.of(System.getProperty("user.home")),
        env: (String) -> String? = System::getenv,
    ): Path? = when (os) {
        HostOs.MAC -> home.resolve("Library/Application Support/Pebble SDK")
        HostOs.LINUX -> {
            val legacy = home.resolve(".pebble-sdk")
            if (Files.isDirectory(legacy)) {
                legacy
            } else {
                val dataHome = env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of)
                    ?: home.resolve(".local/share")
                dataHome.resolve("pebble-sdk")
            }
        }
        HostOs.WINDOWS -> null
    }
}
