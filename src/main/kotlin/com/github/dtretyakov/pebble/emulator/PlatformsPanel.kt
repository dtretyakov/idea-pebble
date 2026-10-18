package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanelWithEmptyText
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.ui.dsl.builder.HyperlinkEventAction
import com.intellij.ui.dsl.builder.MAX_LINE_LENGTH_WORD_WRAP
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JViewport
import javax.swing.ScrollPaneConstants
import javax.swing.Scrollable
import javax.swing.SwingConstants

/**
 * The All Platforms tab: the app on every platform it targets, side by side — the last capture of
 * each with the watch it stands for, all at one scale so their sizes compare, wrapped to the tab's
 * width: it scrolls down, never across.
 *
 * The comparison the Screen tab cannot make: a layout that fits emery's 200×228 and overflows
 * aplite's 144×168, or text a round screen cuts off. A preview only, kept in the
 * [PlatformPreview]; the app store's screenshots are `pebble publish`'s business.
 *
 * A platform the app targets without a capture keeps its place, empty, with the way to fill it.
 */
class PlatformsPanel(private val project: Project) : JBPanelWithEmptyText(BorderLayout()) {

    private val grid = Grid().apply { border = JBUI.Borders.empty(0, 4, 12, 4) }

    init {
        reload()
    }

    /** Reads the preview again; after a capture, or when the tab is shown. */
    fun reload() {
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot()
        val targets = root?.let(model::platforms).orEmpty()
        show(targets, root?.let { PlatformPreview.latest(it, targets) }.orEmpty())
    }

    internal fun show(targets: List<String>, shots: List<PlatformScreenshots.Shot>) {
        removeAll()
        if (shots.isEmpty()) {
            describe(targets)
        } else {
            grid.removeAll()
            val byPlatform = shots.associateBy { it.platform }
            (targets + shots.map { it.platform }.filterNot { it in targets }).forEach { platform ->
                val shot = byPlatform[platform]
                val image = shot?.let { runCatching { ImageIO.read(it.file.toFile()) }.getOrNull() }
                grid.add(if (shot != null && image != null) card(shot, image) else missing(platform))
            }
            val sheet = Sheet().apply {
                add(header(), BorderLayout.NORTH)
                add(grid, BorderLayout.CENTER)
            }
            add(
                JBScrollPane(sheet, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)
                    .apply { border = JBUI.Borders.empty() },
                BorderLayout.CENTER,
            )
        }
        revalidate()
        repaint()
    }

    /**
     * What the tab is for, the platforms it would show and the one action that fills it, with
     * what that costs: said here rather than in a dialog after the click.
     */
    private fun describe(targets: List<String>) {
        emptyText.clear()
        emptyText.appendLine("Your app on every watch it targets", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES, null)
        targets.mapNotNull(PebblePlatform::of).forEach {
            emptyText.appendLine("${it.id} · ${it.watches} · ${it.width}×${it.height}", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        }
        emptyText.appendLine(" ")
        emptyText.appendLine("One screenshot of each, side by side, to catch a layout")
        emptyText.appendLine("that breaks on a round or a smaller screen.")
        emptyText.appendLine(" ")
        emptyText.appendLine(CaptureAllPlatformsAction.TEXT, SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) { CaptureAllPlatformsAction.start(project) }
        emptyText.appendLine(CaptureAllPlatformsAction.cost(targets.size), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES, null)
    }

    /** How old the pictures are and how to renew them; wrapped, as the tab is narrow. */
    private fun header(): JComponent = panel {
        row {
            comment(
                "The last capture of each platform. After a change, <a href='capture'>${CaptureAllPlatformsAction.TEXT}</a> again.",
                maxLineLength = MAX_LINE_LENGTH_WORD_WRAP,
                action = HyperlinkEventAction { CaptureAllPlatformsAction.start(project) },
            )
        }
    }.apply { border = JBUI.Borders.empty(4, 12, 0, 12) }

    private fun card(shot: PlatformScreenshots.Shot, image: BufferedImage): JComponent {
        val screen = Screen(grid, image.width, image.height, image)
        return Card(Dimension(image.width, image.height), screen, caption(shot.platform, taken(shot.taken))).apply {
            toolTipText = shot.file.toString()
            addMouseListener(
                object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        if (e.clickCount != 2) return
                        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(shot.file)
                            ?.let { FileEditorManager.getInstance(project).openFile(it, true) }
                    }
                },
            )
        }
    }

    /**
     * A platform the app targets with no screenshot yet: its screen's outline, dashed, with the way
     * to fill it inside — so its card is as tall as the others and the captions stay in line.
     */
    private fun missing(platform: String): JComponent {
        val known = PebblePlatform.of(platform)
        val width = known?.width ?: 144
        val height = known?.height ?: 168
        val screen = Screen(grid, width, height, image = null).apply {
            add(ActionLink("Capture") { CaptureAllPlatformsAction.start(project) })
        }
        return Card(Dimension(width, height), screen, caption(platform, "no screenshot yet"))
    }

    /** The platform, its watches, its size and [note], centred, a label a line. */
    private fun caption(platform: String, note: String): JComponent = JPanel(VerticalLayout(0, SwingConstants.CENTER)).apply {
        val known = PebblePlatform.of(platform)
        add(JBLabel(platform).apply { font = font.deriveFont(Font.BOLD) })
        listOfNotNull(known?.watches, known?.let { "${it.width}×${it.height}" }, note).forEach { add(JBLabel(it)) }
    }

    /** A screen and what is said under it; [screen] is its size in the watch's pixels. */
    internal class Card(val screen: Dimension, picture: JComponent, private val below: JComponent) : JPanel(BorderLayout(0, JBUI.scale(4))) {
        init {
            add(picture, BorderLayout.CENTER)
            add(below, BorderLayout.SOUTH)
        }

        fun widthAt(scale: Int): Int = maxOf(screen.width * scale, below.preferredSize.width)

        /** The caption and the space above it: what a card adds to its screen's height. */
        val belowHeight: Int get() = below.preferredSize.height + (layout as BorderLayout).vgap
    }

    /**
     * The cards in rows as wide as the tab, each row centred, at one scale for all: twice the
     * watch's pixels when every card fits in a row, its own pixels otherwise — whole multiples, so
     * the pixels stay square. A row is as tall as its tallest screen, and its screens stand on one
     * line, so their captions do too; a card alone on its row is centred under the others.
     */
    private class Grid : JPanel(null) {
        var scale = SCALE
            private set

        private val cards get() = components.filterIsInstance<Card>()
        private val gap get() = JBUI.scale(16)

        /** The width the preferred height was measured at; laid out at another, it is measured again. */
        private var measuredAt = -1

        private fun room(width: Int) = (width - insets.left - insets.right).takeIf { it > 0 } ?: Int.MAX_VALUE

        /**
         * Twice the pixels when one row takes every card. Back to twice only with a scroll bar's
         * width to spare: a scroll bar coming and going with the height would otherwise flip it.
         */
        private fun scaleFor(room: Int): Int {
            val row = cards.sumOf { it.widthAt(SCALE) } + gap * (cards.size - 1)
            val spare = if (scale == SCALE) 0 else JBUI.scale(24)
            return if (row + spare <= room) SCALE else 1
        }

        private fun rows(room: Int, scale: Int): List<List<Card>> {
            val rows = mutableListOf<MutableList<Card>>()
            var used = 0
            cards.forEach { card ->
                val width = card.widthAt(scale)
                if (rows.isEmpty() || used + gap + width > room) {
                    rows += mutableListOf<Card>()
                    used = width
                } else {
                    used += gap + width
                }
                rows.last() += card
            }
            return rows
        }

        private fun rowHeight(row: List<Card>, scale: Int) = row.maxOf { it.screen.height } * scale + row.maxOf { it.belowHeight }

        override fun getPreferredSize(): Dimension {
            val width = if (width > 0) width else parent?.width ?: 0
            measuredAt = width
            val room = room(width)
            val scale = scaleFor(room)
            val rows = rows(room, scale)
            val height = rows.sumOf { rowHeight(it, scale) } + gap * (rows.size - 1).coerceAtLeast(0)
            val widest = cards.maxOfOrNull { it.widthAt(1) } ?: 0
            return Dimension(widest + insets.left + insets.right, height + insets.top + insets.bottom)
        }

        override fun getMinimumSize() = preferredSize

        override fun doLayout() {
            val room = room(width)
            val wanted = scaleFor(room)
            if (wanted != scale || width != measuredAt) {
                scale = wanted
                revalidate()
            }
            var y = insets.top
            rows(room, scale).forEach { row ->
                val height = rowHeight(row, scale)
                val total = row.sumOf { it.widthAt(scale) } + gap * (row.size - 1)
                var x = insets.left + ((room - total) / 2).coerceAtLeast(0)
                row.forEach { card ->
                    val width = card.widthAt(scale)
                    card.setBounds(x, y, width, height)
                    card.revalidate()
                    x += width + gap
                }
                y += height + gap
            }
        }
    }

    /**
     * A screen at the grid's scale, pixels kept square, centred and standing on its row's line;
     * with no [image], its outline, dashed, around what it holds — where the screenshot will go.
     */
    private class Screen(private val grid: Grid, private val w: Int, private val h: Int, private val image: BufferedImage?) : JPanel(null) {
        init {
            isOpaque = false
        }

        override fun getPreferredSize() = Dimension(w * grid.scale, h * grid.scale)

        override fun getMinimumSize() = preferredSize

        private fun area(): Rectangle {
            val scale = grid.scale
            return Rectangle((width - w * scale) / 2, height - h * scale, w * scale, h * scale)
        }

        override fun doLayout() {
            val area = area()
            components.forEach {
                val size = it.preferredSize
                it.setBounds(area.x + (area.width - size.width) / 2, area.y + (area.height - size.height) / 2, size.width, size.height)
            }
        }

        override fun paintComponent(g: Graphics) {
            val area = area()
            val g2 = g.create() as Graphics2D
            try {
                if (image != null) {
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
                    g2.drawImage(image, area.x, area.y, area.width, area.height, null)
                } else {
                    g2.color = UIUtil.getContextHelpForeground()
                    g2.stroke = BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, floatArrayOf(4f, 4f), 0f)
                    g2.drawRect(area.x, area.y, area.width - 1, area.height - 1)
                }
            } finally {
                g2.dispose()
            }
        }
    }

    /** What the scroll pane holds: as wide as the tab, so it scrolls down and never across. */
    private class Sheet : JPanel(BorderLayout()), Scrollable {
        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visible: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visible: Rectangle, orientation: Int, direction: Int) =
            if (orientation == SwingConstants.VERTICAL) visible.height else visible.width
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = (parent as? JViewport)?.let { it.height > preferredSize.height } ?: false
    }

    private companion object {
        const val SCALE = 2
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        val SHOWN: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US)

        /** When a screenshot was taken, from its name, as a reader would say it. */
        fun taken(stamp: String): String =
            runCatching { LocalDateTime.parse(stamp, STAMP).format(SHOWN) }.getOrDefault(stamp)
    }
}
