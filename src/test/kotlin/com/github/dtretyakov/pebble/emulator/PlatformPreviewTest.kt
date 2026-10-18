package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

class PlatformPreviewTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a capture's files leave screenshots, which it made, for the preview`() {
        val before = PlatformPreview.before(root)
        val screenshots = root.resolve("screenshots").createDirectories()
        listOf("emery_1.0.0_20261018-100948.png", "gabbro_1.0.0_20261018-100957.png").forEach { screenshots.resolve(it).createFile() }

        val moved = PlatformPreview.collect(root, before)

        assertEquals(listOf("emery_1.0.0_20261018-100948.png", "gabbro_1.0.0_20261018-100957.png"), moved.map { it.name }.sorted())
        assertFalse(screenshots.exists())
        assertEquals(listOf("emery", "gabbro"), PlatformPreview.latest(root, listOf("emery", "gabbro")).map { it.platform })
    }

    @Test
    fun `the user's own screenshots stay, and a platform's earlier preview goes`() {
        val screenshots = root.resolve("screenshots").createDirectories()
        screenshots.resolve("emery_1.0.0_20261001-120000.png").createFile()
        PlatformPreview.directory(root).createDirectories().resolve("emery_1.0.0_20261017-090000.png").createFile()
        val before = PlatformPreview.before(root)
        screenshots.resolve("emery_1.0.0_20261018-100948.png").createFile()

        PlatformPreview.collect(root, before)

        assertEquals(listOf("emery_1.0.0_20261001-120000.png"), screenshots.listDirectoryEntries().map { it.name })
        assertEquals(listOf("emery_1.0.0_20261018-100948.png"), PlatformPreview.directory(root).listDirectoryEntries().map { it.name })
        assertTrue(Files.isDirectory(screenshots))
    }
}
