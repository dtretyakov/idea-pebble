package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createFile

class PlatformScreenshotsTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `pebble-tool's names are read`() {
        val shot = PlatformScreenshots.parse(Path.of("basalt_1.0.0_20261014-083653.png"))!!
        assertEquals("basalt", shot.platform)
        assertEquals("1.0.0", shot.version)
        assertEquals("20261014-083653", shot.taken)
        assertNull(PlatformScreenshots.parse(Path.of("basalt_1.0.0_20261014-083653.gif")))
        assertNull(PlatformScreenshots.parse(Path.of("basalt.png")))
    }

    @Test
    fun `the Screen tab's former names still count, and its saves now take pebble-tool's`() {
        val former = PlatformScreenshots.parse(Path.of("emery-20261014-083653.png"))!!
        assertEquals("emery", former.platform)
        assertEquals("20261014-083653", former.taken)

        val at = java.time.LocalDateTime.of(2026, 10, 18, 9, 37, 22)
        val name = PlatformScreenshots.fileName("gabbro", "1.2.0", at)
        assertEquals("gabbro_1.2.0_20261018-093722.png", name)
        assertEquals("gabbro", PlatformScreenshots.parse(Path.of(name))!!.platform)
        assertEquals("gabbro_unknown_20261018-093722.png", PlatformScreenshots.fileName("gabbro", null, at))
        assertEquals("gabbro_1.2_beta_20261018-093722.png", PlatformScreenshots.fileName("gabbro", "1.2 beta", at))
    }

    @Test
    fun `the newest of each platform, in the project's order`() {
        listOf(
            "emery_1.0.0_20261014-083702.png", "emery_1.0.0_20261014-090000.png",
            "basalt_1.0.0_20261014-083653.png", "gabbro_0.9_20261001-120000.png", "notes.txt",
        ).forEach { dir.resolve(it).createFile() }

        val latest = PlatformScreenshots.latest(dir, listOf("gabbro", "basalt"))
        assertEquals(listOf("gabbro", "basalt", "emery"), latest.map { it.platform })
        assertEquals("20261014-090000", latest.last().taken)
    }
}
