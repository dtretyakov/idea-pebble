package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.UvRelease
import com.intellij.util.io.Compressor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class ManagedUvTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `every system the SDK runs on has a uv, and Windows has none`() {
        for (os in listOf(HostOs.MAC, HostOs.LINUX)) {
            for (arch in listOf("aarch64", "amd64")) assertNotNull(UvRelease.forPlatform(os, arch), "$os $arch")
        }
        assertNull(UvRelease.forPlatform(HostOs.WINDOWS, "amd64"))
    }

    @Test
    fun `an archive that hashes to the pinned checksum is unpacked, and uv is executable`() {
        val archive = archive()
        val asset = UvRelease.Asset("uv-test.tar.gz", ManagedUv.sha256(archive))
        val uv = ManagedUv.install(asset, dir.resolve("into")) { _, to -> Files.copy(archive, to) }
        assertEquals(dir.resolve("into/uv"), uv)
        assertTrue(Files.isExecutable(uv))
    }

    @Test
    fun `an archive that does not is refused, and nothing is installed`() {
        val archive = archive()
        val asset = UvRelease.Asset("uv-test.tar.gz", "0".repeat(64))
        assertThrows(IllegalStateException::class.java) {
            ManagedUv.install(asset, dir.resolve("into")) { _, to -> Files.copy(archive, to) }
        }
        assertFalse(dir.resolve("into/uv").exists())
    }

    /** Shaped like uv's: the binaries inside a directory named after the platform. */
    private fun archive(): Path {
        val archive = dir.resolve("uv.tar.gz")
        Compressor.Tar(archive, Compressor.Tar.Compression.GZIP).use {
            it.addFile("uv-aarch64-apple-darwin/uv", "#!/bin/sh\n".toByteArray())
            it.addFile("uv-aarch64-apple-darwin/uvx", "#!/bin/sh\n".toByteArray())
        }
        return archive
    }
}
