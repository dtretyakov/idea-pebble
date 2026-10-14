package com.github.dtretyakov.pebble.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** What the package.json inspection says of a resource's file, against what the build does. */
class ResourcesLiveTest {

    @Test
    fun `a resource builds from its platform variants, and fails the build when it has none`() {
        val root = LiveSdk.fixture("fixture-c")
        val images = root.resolve("resources/images").createDirectories()
        listOf("icon~color.png", "icon~bw.png").forEach { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", images.resolve(it).toFile()) }

        declare(root, """{"type": "bitmap", "name": "ICON", "file": "images/icon.png"}""")
        val variants = LiveSdk.run(root, "build")
        assertEquals(0, variants.exitCode, variants.text)

        declare(root, """{"type": "bitmap", "name": "ICON", "file": "images/icon.png"}, {"type": "bitmap", "name": "LOGO", "file": "images/logo.png"}""")
        val missing = LiveSdk.run(root, "build")
        assertNotEquals(0, missing.exitCode, missing.text)
        assertTrue("resources/images/logo.png" in missing.text, missing.text)
    }

    private fun declare(root: Path, media: String) {
        val packageJson = root.resolve("package.json")
        packageJson.writeText(
            packageJson.readText()
                .replace(Regex(""""media": \[[^]]*]"""), """"media": [$media]""")
                .replace(Regex(""""targetPlatforms": \[[^]]*]"""), """"targetPlatforms": ["aplite", "basalt"]"""),
        )
    }
}
