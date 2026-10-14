package com.github.dtretyakov.pebble.build

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.outputStream

class ReleaseCheckTest {

    private fun json(version: String?, uuid: String?) =
        """{"name": "a", ${version?.let { "\"version\": \"$it\"," } ?: ""} "pebble": {${uuid?.let { "\"uuid\": \"$it\"" } ?: ""}}}"""

    private fun List<ReleaseCheck.Item>.of(check: String) = single { it.check == check }

    @Test
    fun `a version of numbers and dots, each 255 or less, and a UUID`() {
        val uuid = "6f1d9a43-0d1c-4a8e-9a33-0b1f4c5d7e21"
        assertEquals("ok", ReleaseCheck.packageJson(json("1.0", uuid)).of("version").severity)
        assertEquals("ok", ReleaseCheck.packageJson(json("2.255.0", uuid)).of("version").severity)
        assertEquals("error", ReleaseCheck.packageJson(json("1.0-beta", uuid)).of("version").severity)
        assertEquals("error", ReleaseCheck.packageJson(json("1.256", uuid)).of("version").severity)
        assertEquals("error", ReleaseCheck.packageJson(json(null, uuid)).of("version").severity)
        assertEquals("ok", ReleaseCheck.packageJson(json("1.0", uuid)).of("uuid").severity)
        assertEquals("error", ReleaseCheck.packageJson(json("1.0", "not-a-uuid")).of("uuid").severity)
        assertEquals("error", ReleaseCheck.packageJson(json("1.0", null)).of("uuid").severity)
        assertEquals("error", ReleaseCheck.packageJson("{").single().severity)
    }

    private fun pbw(file: Path, platforms: List<String>, padding: Int = 0): Path {
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.setLevel(0)
            zip.putNextEntry(ZipEntry("appinfo.json")); zip.write("{}".toByteArray()); zip.closeEntry()
            for (platform in platforms) {
                zip.putNextEntry(ZipEntry("$platform/pebble-app.bin")); zip.write(ByteArray(16)); zip.closeEntry()
            }
            if (padding > 0) {
                zip.putNextEntry(ZipEntry("emery/app_resources.pbpack")); zip.write(java.util.Random(1).let { r -> ByteArray(padding).also(r::nextBytes) }); zip.closeEntry()
            }
        }
        return file
    }

    @Test
    fun `the bundle has every target platform, and fits`(@TempDir temp: Path) {
        val good = ReleaseCheck.bundle(pbw(temp.resolve("a.pbw"), listOf("basalt", "emery")), listOf("basalt", "emery"))
        assertTrue(good.all { it.severity == "ok" }, good.toString())
        val missing = ReleaseCheck.bundle(pbw(temp.resolve("b.pbw"), listOf("basalt")), listOf("basalt", "emery")).of("platforms")
        assertEquals("error", missing.severity)
        assertTrue("emery" in missing.message)
        val big = ReleaseCheck.bundle(pbw(temp.resolve("c.pbw"), listOf("emery"), padding = 4_500_000), listOf("emery")).of("bundle size")
        assertEquals("error", big.severity)
        assertEquals("error", ReleaseCheck.bundle(null, listOf("emery")).single().severity)
    }

    @Test
    fun `resources over the store's limit are refused, a short heap is a warning`() {
        val over = MemoryUsage("aplite", "app", 1000, 24576, 20000, 200_000, 131_072)
        val tight = MemoryUsage("basalt", "app", 60000, 65536, 1000, 100, 262_144)
        val fine = MemoryUsage("emery", "app", 1000, 131072, 120000, 100, 262_144)
        assertEquals(listOf("error" to "aplite", "warning" to "basalt"), ReleaseCheck.memory(listOf(over, tight, fine)).map { it.severity to it.message.substringBefore(':') })
    }

    private fun png(file: Path, width: Int, height: Int) = file.also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it.toFile()) }

    @Test
    fun `screenshots at each platform's own size, and at least one`(@TempDir root: Path) {
        assertEquals("error", ReleaseCheck.screenshots(root.resolve("screenshots"), listOf("basalt")).last().severity)
        val directory = root.resolve("screenshots").createDirectories()
        png(directory.resolve("basalt_4.33.1_20261014-120000.png"), 144, 168)
        png(directory.resolve("chalk_4.33.1_20261014-120000.png"), 144, 168)
        val items = ReleaseCheck.screenshots(directory, listOf("basalt", "chalk", "emery"))
        assertEquals(listOf("ok", "error", "warning"), items.map { it.severity }, items.toString())
        assertTrue(items[1].message.contains("180×180"))
        assertTrue(items[2].message.contains("emery"))
        repeat(6) { png(directory.resolve("basalt_4.33.1_20261014-12000$it.png"), 144, 168) }
        assertEquals("warning", ReleaseCheck.screenshots(directory, listOf("basalt")).single().severity)
        assertTrue(Files.exists(directory))
    }
}
