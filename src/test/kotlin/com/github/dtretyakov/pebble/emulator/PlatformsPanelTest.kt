package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO

/** The Platforms tab: a card for each platform's newest screenshot, named after its watches. */
class PlatformsPanelTest : IdeTestCase() {

    fun `test a card for each platform, and a hint when there is none`() {
        val directory = Files.createTempDirectory("screenshots")
        listOf("basalt" to (144 to 168), "emery" to (200 to 228)).forEach { (platform, size) ->
            ImageIO.write(BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("${platform}_1.0.0_20261014-083653.png").toFile())
        }
        val panel = PlatformsPanel(project)

        panel.show(PlatformScreenshots.latest(directory, listOf("emery", "basalt")))
        val captions = UIUtil.findComponentsOfType(panel, JBLabel::class.java).map { it.text }
        assertEquals(2, captions.size)
        assertTrue(captions[0], captions[0].contains("emery") && captions[0].contains("200×228"))
        assertTrue(captions[1], captions[1].contains("basalt") && captions[1].contains("Pebble Time"))

        panel.show(emptyList())
        assertTrue(UIUtil.findComponentsOfType(panel, JBLabel::class.java).single().text.startsWith("No screenshots yet"))
    }
}
