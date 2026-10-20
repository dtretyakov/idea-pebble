package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.github.dtretyakov.pebble.sdk.UvRelease
import com.intellij.openapi.application.PathManager
import com.intellij.util.io.Decompressor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * The plugin's own uv, for a machine that has none: downloaded only when Set Up Pebble SDK is
 * clicked, and checked against [UvRelease]'s checksum before it is unpacked.
 *
 * Kept in JetBrains' common data directory rather than this IDE's, which changes with every IDE
 * version, and never put on the PATH or into a shell profile — uv's own installer does both, which
 * is a change to the machine nobody asked for. pebble-tool, which this uv installs, does go where
 * uv always puts tools, `~/.local/bin`, so `pebble` works in a terminal as the SDK's documentation
 * says it will.
 */
object ManagedUv {

    fun directory(): Path = PathManager.getCommonDataPath().resolve("pebble").resolve("uv")

    /**
     * Where the uv for [host] goes: this machine's, or — for a WSL distribution — a Linux uv on
     * the Windows disk, which the distribution runs from `/mnt/c/…`. Kept apart by processor, so a
     * second distribution on another one does not take the first one's.
     */
    fun directory(host: ToolHost?): Path =
        if (host == null || host is LocalToolHost) directory() else directory().resolveSibling("uv-linux-${host.arch.ifEmpty { "x86_64" }}")

    /** The plugin's uv, if it has installed one. */
    fun locate(): Path? = directory().resolve("uv").takeIf { Files.isExecutable(it) }

    /** The plugin's uv for [host]. On the Windows disk an executable bit is the distribution's business, not the file's. */
    fun locate(host: ToolHost?): Path? =
        if (host == null || host is LocalToolHost) locate() else directory(host).resolve("uv").takeIf { Files.isRegularFile(it) }

    /**
     * Downloads [asset], checks it, and puts `uv` in [into]. [download] fetches a URL to a file;
     * a parameter so the rest can be tested without a network. Work happens in a temporary
     * directory, so a failure at any step leaves [into] as it was.
     */
    fun install(asset: UvRelease.Asset, into: Path = directory(), download: (url: String, to: Path) -> Unit): Path {
        val work = Files.createTempDirectory("pebble-uv-")
        try {
            val archive = work.resolve(asset.file)
            download(asset.url, archive)

            val actual = sha256(archive)
            check(actual.equals(asset.sha256, ignoreCase = true)) {
                "The download of ${asset.file} is not the file this plugin expects (SHA-256 $actual), so it was not used."
            }

            val unpacked = work.resolve("unpacked")
            Decompressor.Tar(archive).extract(unpacked)
            // The archive holds a directory named after the platform; found by name, not by path.
            val uv = Files.walk(unpacked).use { paths ->
                paths.filter { it.name == "uv" && it.isRegularFile() }.findFirst().orElse(null)
            } ?: error("${asset.file} has no uv in it.")

            into.createDirectories()
            val staged = Files.createTempFile(into, "uv", ".part")
            Files.copy(uv, staged, StandardCopyOption.REPLACE_EXISTING)
            staged.toFile().setExecutable(true, false)
            val target = into.resolve("uv")
            Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            return target
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    internal fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
