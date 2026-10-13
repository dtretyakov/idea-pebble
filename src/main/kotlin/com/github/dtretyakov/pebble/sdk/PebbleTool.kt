package com.github.dtretyakov.pebble.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `pebble` command — pebble-tool — which everything the plugin runs goes through.
 *
 * It is installed with `uv tool install pebble-tool`, which puts it in uv's bin directory: that is
 * `~/.local/bin` unless `UV_TOOL_BIN_DIR` or `XDG_BIN_HOME` says otherwise, and it is on the PATH
 * of a login shell but not necessarily on the PATH of an IDE started from the Dock or a desktop
 * launcher. So the PATH is the first place to look and not the only one.
 */
object PebbleTool {

    const val INSTALL_URL = "https://developer.repebble.com/sdk/"

    /**
     * Where to find `pebble`, in order: what the user configured, the PATH, then uv's bin
     * directories. A configured path that is not there is not silently replaced by a found one —
     * the user asked for that one, and should be told it is missing.
     */
    fun locate(
        configured: String,
        path: String?,
        home: Path = Path.of(System.getProperty("user.home")),
        env: (String) -> String? = System::getenv,
    ): Path? {
        if (configured.isNotBlank()) return Path.of(configured).takeIf(::isExecutable)

        val fromPath = path.orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }
            .map { Path.of(it).resolve(EXECUTABLE) }
        val fromUv = listOfNotNull(
            env("UV_TOOL_BIN_DIR")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(EXECUTABLE) },
            env("XDG_BIN_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).resolve(EXECUTABLE) },
            home.resolve(".local/bin").resolve(EXECUTABLE),
        )
        return (fromPath + fromUv).firstOrNull(::isExecutable)
    }

    /**
     * The version out of `pebble --version`, which answers `Pebble Tool v5.0.40 (active SDK:
     * v4.33.1)` — or null for anything else.
     */
    fun parseVersion(output: String): String? =
        Regex("""Pebble Tool v(\S+)""").find(output)?.groupValues?.get(1)

    private const val EXECUTABLE = "pebble"

    private fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)
}
