package com.github.dtretyakov.pebble.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * clangd, which gives C its code insight in the IDEs that have no C engine of their own.
 *
 * Not the SDK's: the SDK ships gcc and no clangd. Distributions install it under a versioned name
 * (`clangd-18`) as often as a plain one, and Homebrew keeps LLVM off the PATH, so those are
 * looked for too.
 */
object Clangd {

    const val INSTALL_URL = "https://clangd.llvm.org/installation"

    fun locate(configured: String, path: String?): Path? {
        if (configured.isNotBlank()) return Path.of(configured).takeIf(::isExecutable)

        val directories = path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }.map(Path::of) +
            HOMEBREW.map(Path::of)
        val names = listOf("clangd") + (MAX_VERSION downTo MIN_VERSION).map { "clangd-$it" }
        return names.asSequence()
            .flatMap { name -> directories.asSequence().map { it.resolve(name) } }
            .firstOrNull(::isExecutable)
    }

    private val HOMEBREW = listOf("/opt/homebrew/opt/llvm/bin", "/usr/local/opt/llvm/bin")

    /** 14 is the oldest that reloads a changed compile_commands.json without a restart. */
    private const val MIN_VERSION = 14
    private const val MAX_VERSION = 24

    private fun isExecutable(path: Path): Boolean = Files.isRegularFile(path) && Files.isExecutable(path)
}
