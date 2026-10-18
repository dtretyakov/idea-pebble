package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.UIUtil
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.swing.JEditorPane
import javax.swing.JScrollPane
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

/** The All Platforms tab: a card for each platform the app targets, and what it is for when empty. */
class PlatformsPanelTest : IdeTestCase() {

    fun `test a card for each platform, an empty place for one not captured, and where the files are`() {
        val directory = Files.createTempDirectory("screenshots")
        listOf("basalt" to (144 to 168), "emery" to (200 to 228)).forEach { (platform, size) ->
            ImageIO.write(BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("${platform}_1.0.0_20261014-083653.png").toFile())
        }
        val panel = PlatformsPanel(project)

        panel.show(listOf("emery", "gabbro", "basalt"), PlatformScreenshots.latest(directory, listOf("emery", "gabbro", "basalt")))
        val captions = cards(panel).map { card -> UIUtil.findComponentsOfType(card, JBLabel::class.java).joinToString(" / ") { it.text } }
        assertEquals(3, captions.size)
        assertTrue(captions[0], captions[0].contains("emery") && captions[0].contains("200×228") && captions[0].contains("Oct 14, 08:36"))
        assertTrue(captions[1], captions[1].contains("gabbro") && captions[1].contains("no screenshot yet"))
        assertTrue(captions[2], captions[2].contains("basalt") && captions[2].contains("Pebble Time"))
        val header = UIUtil.findComponentsOfType(panel, JEditorPane::class.java).joinToString { it.text }.replace(Regex("""\s+"""), " ")
        assertTrue(header, header.contains("The last capture of each platform") && header.contains(CaptureAllPlatformsAction.TEXT))
        assertFalse(header, header.contains("screenshots/") || header.contains("store"))
        val links = UIUtil.findComponentsOfType(panel, ActionLink::class.java).map { it.text }
        assertEquals(listOf("Capture"), links)
    }

    fun `test it wraps to the tab's width rather than scrolling across, and halves the scale to fit`() {
        val directory = Files.createTempDirectory("screenshots")
        listOf("basalt" to (144 to 168), "emery" to (200 to 228)).forEach { (platform, size) ->
            ImageIO.write(BufferedImage(size.first, size.second, BufferedImage.TYPE_INT_RGB), "png", directory.resolve("${platform}_1.0.0_20261014-083653.png").toFile())
        }
        val panel = PlatformsPanel(project)
        panel.show(listOf("emery", "gabbro", "basalt"), PlatformScreenshots.latest(directory, listOf("emery", "gabbro", "basalt")))
        val scroll = UIUtil.findComponentOfType(panel, JScrollPane::class.java)!!
        assertEquals(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER, scroll.horizontalScrollBarPolicy)

        fun layOut(width: Int): List<Rectangle> {
            panel.setSize(width, 2000)
            repeat(3) { UIUtil.uiTraverser(panel).forEach { (it as? java.awt.Container)?.doLayout() } }
            return cards(panel).map { SwingUtilities.convertRectangle(it.parent, it.bounds, panel) }
        }

        val wide = layOut(1600)
        assertEquals("one row when wide", 1, wide.map { it.y }.distinct().size)

        val narrow = layOut(500)
        assertTrue(narrow.toString(), narrow.all { it.x >= 0 && it.x + it.width <= 500 })
        assertTrue("wraps when narrow: $narrow", narrow.map { it.y }.distinct().size > 1)
        assertEquals("gabbro at its own pixels: $narrow", 260, narrow[1].width)
        assertEquals("cards sharing a row share its height: $narrow", narrow[0].height, narrow[1].height)

        val column = layOut(400)
        assertEquals("a card alone on its row is centred under the others: $column", 1, column.map { it.x + it.width / 2 }.distinct().size)
        assertEquals("a row is as tall as its own screen: $column", 228 - 168, column[0].height - column[2].height)
    }

    private fun cards(panel: PlatformsPanel) = UIUtil.findComponentsOfType(panel, PlatformsPanel.Card::class.java)

    fun `test when empty, it says what it is for, for which watches, and how to fill it`() {
        val panel = PlatformsPanel(project)
        panel.show(listOf("emery", "gabbro"), emptyList())

        assertEquals(0, panel.componentCount)
        val text = panel.emptyText.toString()
        assertTrue(text, text.contains("Your app on every watch it targets"))
        assertTrue(text, text.contains("gabbro · Pebble Round 2 · 260×260"))
        assertTrue(text, text.contains("round or a smaller screen"))
        assertFalse(text, text.contains("app store"))
        assertTrue(text, text.contains(CaptureAllPlatformsAction.TEXT))
        assertTrue(text, text.contains("About 30 seconds"))
    }
}
