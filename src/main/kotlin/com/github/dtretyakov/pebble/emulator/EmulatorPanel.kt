package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.StartupUiUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * The emulator's screen, and the watch's four buttons beside it where the watch has them: Back on
 * the left, Up, Select and Down on the right.
 *
 * The screen takes keyboard focus on a click, and then the arrow keys are the buttons — Left for
 * Back, Right for Select — as in the emulator's own window. A click on a touch platform's screen
 * is a touch.
 */
class EmulatorPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val session = EmulatorSession.getInstance(project)
    private val screen = Screen()

    init {
        add(column(button("Back", RfbClient.KEY_LEFT)), BorderLayout.WEST)
        add(screen, BorderLayout.CENTER)
        add(
            column(button("Up", RfbClient.KEY_UP), button("Select", RfbClient.KEY_RIGHT), button("Down", RfbClient.KEY_DOWN)),
            BorderLayout.EAST,
        )

        session.addListener(
            object : EmulatorSession.Listener {
                override fun frame(emulator: RunningEmulator, image: BufferedImage) = screen.show(emulator, image)
                override fun idle() = screen.show(null, null)
            },
            parent,
        )
    }

    /** The screen as it is now, cropped to the watch's own pixels; null when there is none. */
    fun screenshot(): BufferedImage? = screen.cropped()

    private fun column(vararg buttons: JComponent) = JPanel(GridLayout(buttons.size, 1, 0, JBUI.scale(8))).apply {
        border = JBUI.Borders.empty(8)
        buttons.forEach(::add)
        // Centred vertically, like the buttons on a watch's side.
    }.let { inner -> JPanel(java.awt.GridBagLayout()).apply { add(inner) } }

    /** Held down for as long as the mouse is: long presses matter on a watch. */
    private fun button(text: String, keysym: Int) = JButton(text).apply {
        isFocusable = false
        addMouseListener(
            object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    session.client?.key(keysym, true)
                }

                override fun mouseReleased(e: MouseEvent) {
                    session.client?.key(keysym, false)
                }
            },
        )
    }

    private inner class Screen : JComponent() {

        @Volatile
        private var emulator: RunningEmulator? = null

        @Volatile
        private var image: BufferedImage? = null

        init {
            isFocusable = true
            preferredSize = Dimension(JBUI.scale(220), JBUI.scale(240))
            addMouseListener(
                object : MouseAdapter() {
                    override fun mousePressed(e: MouseEvent) {
                        requestFocusInWindow()
                        touch(e, pressed = true)
                    }

                    override fun mouseReleased(e: MouseEvent) = touch(e, pressed = false)

                    override fun mouseDragged(e: MouseEvent) = touch(e, pressed = true, dragging = true)
                }.also(::addMouseMotionListener),
            )
            addKeyListener(
                object : KeyAdapter() {
                    override fun keyPressed(e: KeyEvent) = key(e, down = true)
                    override fun keyReleased(e: KeyEvent) = key(e, down = false)
                },
            )
        }

        fun show(emulator: RunningEmulator?, image: BufferedImage?) {
            this.emulator = emulator
            this.image = image
            SwingUtilities.invokeLater(::repaint)
        }

        fun cropped(): BufferedImage? {
            val image = image ?: return null
            val area = area(image) ?: return null
            return BufferedImage(area.width, area.height, BufferedImage.TYPE_INT_RGB).also {
                it.createGraphics().apply { drawImage(image.getSubimage(area.x, area.y, area.width, area.height), 0, 0, null) }.dispose()
            }
        }

        private fun area(image: BufferedImage): Rectangle? =
            emulator?.let { EmulatorScreen.of(it.platform, image.width, image.height) }

        /** The largest whole-number scale that fits, so pixels stay square and sharp. */
        private fun placement(area: Rectangle): Rectangle {
            val scale = minOf(width / area.width, height / area.height).coerceIn(1, MAX_SCALE)
            val w = area.width * scale
            val h = area.height * scale
            return Rectangle((width - w) / 2, (height - h) / 2, w, h)
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g as Graphics2D
            g2.color = JBColor.background()
            g2.fillRect(0, 0, width, height)
            val image = image
            val area = image?.let(::area)
            if (image == null || area == null) {
                g2.color = JBColor.GRAY
                g2.font = StartupUiUtil.labelFont
                val text = "No emulator. Run the app on the emulator to see it here."
                val metrics = g2.fontMetrics
                g2.drawString(text, ((width - metrics.stringWidth(text)) / 2).coerceAtLeast(4), height / 2)
                return
            }
            val target = placement(area)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
            g2.drawImage(
                image,
                target.x, target.y, target.x + target.width, target.y + target.height,
                area.x, area.y, area.x + area.width, area.y + area.height,
                null,
            )
        }

        private fun touch(e: MouseEvent, pressed: Boolean, dragging: Boolean = false) {
            val emulator = emulator ?: return
            if (PebblePlatform.of(emulator.platform)?.touch != true) return
            val image = image ?: return
            val area = area(image) ?: return
            val target = placement(area)
            // A press starts on the screen; a drag or a release may wander off it and is clamped.
            if (pressed && !dragging && !target.contains(e.point)) return
            val x = (e.x - target.x) * area.width / target.width
            val y = (e.y - target.y) * area.height / target.height
            val pointer = EmulatorScreen.pointerFor(area, image.width, image.height, x, y)
            session.client?.pointer(pointer.x, pointer.y, pressed)
        }

        private fun key(e: KeyEvent, down: Boolean) {
            val keysym = when (e.keyCode) {
                KeyEvent.VK_LEFT, KeyEvent.VK_ESCAPE, KeyEvent.VK_BACK_SPACE -> RfbClient.KEY_LEFT
                KeyEvent.VK_UP -> RfbClient.KEY_UP
                KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER -> RfbClient.KEY_RIGHT
                KeyEvent.VK_DOWN -> RfbClient.KEY_DOWN
                else -> return
            }
            session.client?.key(keysym, down)
            e.consume()
        }
    }

    private companion object {
        const val MAX_SCALE = 4
    }
}
