package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.ui.table.TableView
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Container
import javax.swing.JComponent
import javax.swing.JEditorPane

/** The settings page is built inside a real IDE: a UI that compiles is not a UI that builds. */
class PebbleConfigurableTest : IdeTestCase() {

    fun `test the page builds, with the SDK list enabled only when pebble-tool is here`() {
        withPage { component ->
            val table = UIUtil.findComponentsOfType(component, TableView::class.java).single()
            assertEquals(PebbleSdkService.getInstance().sdks().installed.size, table.rowCount)
            assertEquals(listOf("", "Version", "Size", "Platforms"), (0 until table.columnCount).map(table::getColumnName))
            // The mark, the version and the size as narrow as they need: the rest of the width is the platforms'.
            val widths = (0 until table.columnCount).map { table.columnModel.getColumn(it).maxWidth }
            assertTrue(widths.toString(), widths[0] <= JBUI.scale(28) && widths[1] < 150 && widths[2] < 150 && widths[3] > 1000)
            assertEquals(PebbleSdkService.getInstance().tool() != null, table.isEnabled)
        }
    }

    fun `test without pebble-tool the SDK table is there, disabled`() {
        val settings = PebbleAppSettings.getInstance()
        val saved = settings.pebbleToolPath
        // As on a machine without it, CI's among them: a path to nothing finds no pebble-tool.
        settings.pebbleToolPath = "/nonexistent/pebble"
        try {
            withPage { component ->
                assertNull(PebbleSdkService.getInstance().tool())
                assertFalse(UIUtil.findComponentsOfType(component, TableView::class.java).single().isEnabled)
            }
        } finally {
            settings.pebbleToolPath = saved
        }
    }

    /**
     * Nothing on the page is wider than the explanations on it.
     *
     * A page wider than the dialog takes the right of every row off the edge — which is how the
     * link that installs pebble-tool came to be the part of its row nobody could see. Measured
     * against the page's own widest comment rather than a pixel count, because these are
     * proportional fonts and a larger one moves both numbers together. From idea-monkeyc's
     * SettingsPageFitsTest.
     */
    fun `test no row costs more width than the widest comment`() {
        withPage { component ->
            val comments = collect(component).filterIsInstance<JEditorPane>()
            assertFalse("Expected the page to explain itself", comments.isEmpty())

            val widest = comments.maxOf { it.preferredSize.width }
            val page = component.preferredSize.width
            assertTrue(
                "The page wants ${page}px where its widest comment is ${widest}px. Something on it is " +
                    "wider than a sentence — most likely a comment attached to a field cell, or a row " +
                    "with a label, an icon, text and a link side by side.\n" + describe(component),
                page <= widest + INDENTS,
            )
        }
    }

    private fun withPage(check: (JComponent) -> Unit) {
        val configurable = PebbleConfigurable(project)
        try {
            val component = configurable.createComponent() as JComponent
            configurable.reset()
            check(component)
        } finally {
            configurable.disposeUIResources()
        }
    }

    private fun describe(panel: JComponent): String =
        collect(panel)
            .map { it.preferredSize.width to it }
            .sortedByDescending { it.first }
            .take(8)
            .joinToString("\n") { (width, component) ->
                val text = when (component) {
                    is javax.swing.JLabel -> component.text
                    is javax.swing.AbstractButton -> component.text
                    is javax.swing.text.JTextComponent -> component.text
                    else -> null
                }?.let(::plain)
                "  ${width}px  ${component.javaClass.simpleName}${text?.let { ": ${it.take(55)}" } ?: ""}"
            }

    private fun plain(html: String): String =
        html.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").trim()

    private fun collect(root: Container): List<JComponent> {
        val found = mutableListOf<JComponent>()
        root.components.forEach { child ->
            if (child is JComponent) found += child
            if (child is Container) found += collect(child)
        }
        return found
    }

    private companion object {
        /** What group indents and the panel's insets may add; a doubled label column is several times this. */
        const val INDENTS = 80
    }
}
