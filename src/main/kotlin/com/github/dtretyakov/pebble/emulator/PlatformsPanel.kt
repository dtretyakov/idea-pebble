package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.panels.HorizontalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Every platform side by side: the newest screenshot of each, at twice its size, with the watch it
 * stands for. The point of the row is the comparison — a layout that fits emery's 200×228 and
 * overflows aplite's 144×168, or text a round screen cuts off.
 */
class PlatformsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val row = JPanel(HorizontalLayout(JBUI.scale(16), SwingConstants.TOP)).apply { border = JBUI.Borders.empty(12) }

    init {
        add(JBScrollPane(row), BorderLayout.CENTER)
        reload()
    }

    /** Reads `screenshots/` again; after a capture, or when the tab is shown. */
    fun reload() {
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot()
        show(root?.let { PlatformScreenshots.latest(it.resolve(PlatformScreenshots.DIRECTORY), model.platforms(it)) }.orEmpty())
    }

    internal fun show(shots: List<PlatformScreenshots.Shot>) {
        row.removeAll()
        if (shots.isEmpty()) {
            row.add(JBLabel("No screenshots yet. Capture Every Platform puts one of each platform here.").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        }
        shots.forEach { shot -> runCatching { ImageIO.read(shot.file.toFile()) }.getOrNull()?.let { row.add(card(shot, it)) } }
        row.revalidate()
        row.repaint()
    }

    private fun card(shot: PlatformScreenshots.Shot, image: BufferedImage): JComponent {
        val known = PebblePlatform.of(shot.platform)
        val caption = listOfNotNull(shot.platform, known?.watches, known?.let { "${it.width}×${it.height}" })
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(Picture(image), BorderLayout.CENTER)
            add(JBLabel("<html><center><b>${caption[0]}</b><br>${caption.drop(1).joinToString("<br>")}</center></html>", SwingConstants.CENTER), BorderLayout.SOUTH)
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

    /** A screenshot at twice its size, pixels kept square. */
    private class Picture(private val image: BufferedImage) : JComponent() {
        init {
            preferredSize = Dimension(image.width * SCALE, image.height * SCALE)
        }

        override fun paintComponent(g: Graphics) {
            (g as Graphics2D).setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g.drawImage(image, 0, 0, image.width * SCALE, image.height * SCALE, null)
        }

        private companion object {
            const val SCALE = 2
        }
    }
}
