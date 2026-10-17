package com.github.dtretyakov.pebble.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * uv, which pebble-tool is installed with.
 *
 * Its installer puts it in `~/.local/bin` (`~/.cargo/bin` before uv 0.5), and Homebrew in its own
 * prefix; none of these is on the PATH of an IDE started from the Dock, so they are looked in too.
 */
object Uv {

    const val INSTALL_URL = "https://docs.astral.sh/uv/getting-started/installation/"

    fun locate(
        path: String?,
        home: Path = Path.of(System.getProperty("user.home")),
        env: (String) -> String? = System::getenv,
        system: List<Path> = HOMEBREW,
    ): Path? {
        val fromPath = path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(Path::of)
        val known = listOfNotNull(
            env("XDG_BIN_HOME")?.takeIf { it.isNotBlank() }?.let(Path::of),
            home.resolve(".local/bin"),
            home.resolve(".cargo/bin"),
        ) + system
        return (fromPath + known).map { it.resolve(EXECUTABLE) }.firstOrNull(::isExecutable)
    }

    private val HOMEBREW = listOf(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"))

    private val EXECUTABLE = if (HostOs.current() == HostOs.WINDOWS) "uv.exe" else "uv"

    private fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)
}
