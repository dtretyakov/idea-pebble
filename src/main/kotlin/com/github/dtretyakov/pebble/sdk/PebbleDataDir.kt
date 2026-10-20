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
 * Windows has none of its own: the SDK lives inside a WSL distribution, which is a Linux host and
 * answers by the Linux rule — as the IDE reads it, through the distribution's share.
 */
object PebbleDataDir {

    fun locate(
        os: HostOs = HostOs.current(),
        home: Path = Path.of(System.getProperty("user.home")),
        env: (String) -> String? = System::getenv,
    ): Path? = locate(LocalToolHost(os, home, env))

    fun locate(host: ToolHost): Path? = if (host is WslToolHost) {
        // The distribution applied this same rule itself, where the share would have been asked a stat.
        host.probe?.dataDirectory?.let(host::idePath)
    } else when (host.os) {
        HostOs.MAC -> host.home?.resolve("Library/Application Support/Pebble SDK")
        HostOs.LINUX -> host.home?.let { home ->
            val legacy = home.resolve(".pebble-sdk")
            if (Files.isDirectory(legacy)) {
                legacy
            } else {
                val dataHome = host.env("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }?.let(host::idePath)
                    ?: home.resolve(".local/share")
                dataHome.resolve("pebble-sdk")
            }
        }
        HostOs.WINDOWS -> null
    }
}
