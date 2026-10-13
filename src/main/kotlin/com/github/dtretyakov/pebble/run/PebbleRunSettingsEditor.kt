package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.SimpleListCellRenderer
import javax.swing.JList
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import javax.swing.JComponent

class PebbleRunSettingsEditor(private val project: Project) : SettingsEditor<PebbleRunConfiguration>() {

    private val target = ComboBox(PebbleRunTarget.entries.toTypedArray())
    private val platform = ComboBox<String>()
    private val phoneAddress = JBTextField()
    private val adbSerial = JBTextField()
    private val logs = JBCheckBox("Show the app's logs after installing")
    private val debugBuild = JBCheckBox("Debug build (-O0 -DPBL_DEBUG; xsbug for Alloy)")

    init {
        val model = PebbleProject.getInstance(project)
        val platforms = model.primaryRoot()?.let { model.platforms(it) }.orEmpty()
        (listOf(ACTIVE) + platforms).forEach(platform::addItem)
        // A subclass rather than the DSL's renderer builders, which are internal API in 2026.1.
        platform.renderer = object : SimpleListCellRenderer<String>() {
            override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, focused: Boolean) {
                text = if (value == ACTIVE) "The one chosen beside Run" else value
            }
        }
    }

    override fun resetEditorFrom(configuration: PebbleRunConfiguration) {
        val options = configuration.options
        target.selectedItem = options.target
        platform.selectedItem = options.platform.ifBlank { ACTIVE }
        phoneAddress.text = options.phoneAddress
        adbSerial.text = options.adbSerial
        logs.isSelected = options.logs
        debugBuild.isSelected = options.debugBuild
    }

    override fun applyEditorTo(configuration: PebbleRunConfiguration) {
        val options = configuration.options
        options.target = target.selectedItem as PebbleRunTarget
        options.platform = (platform.selectedItem as? String)?.takeIf { it != ACTIVE }.orEmpty()
        options.phoneAddress = phoneAddress.text.trim()
        options.adbSerial = adbSerial.text.trim()
        options.logs = logs.isSelected
        options.debugBuild = debugBuild.isSelected
    }

    override fun createEditor(): JComponent = panel {
        row("Install on:") { cell(target) }
        row("Platform:") { cell(platform) }.visibleIf(targetIs(PebbleRunTarget.EMULATOR))
        row("Phone address:") {
            cell(phoneAddress).align(AlignX.FILL)
                .comment("As the Pebble app shows it under Developer Connection, e.g. 192.168.1.20")
        }.visibleIf(targetIs(PebbleRunTarget.PHONE))
        row("Device serial:") {
            cell(adbSerial).align(AlignX.FILL).comment("Only when more than one Android device is attached")
        }.visibleIf(targetIs(PebbleRunTarget.ADB))
        row { cell(logs) }
        row { cell(debugBuild) }
    }

    private fun targetIs(expected: PebbleRunTarget) = object : ComponentPredicate() {
        override fun invoke(): Boolean = target.selectedItem == expected
        override fun addListener(listener: (Boolean) -> Unit) {
            target.addActionListener { listener(invoke()) }
        }
    }

    private companion object {
        /** The combo's entry for "follow the run widget", stored as an empty platform. */
        const val ACTIVE = ""
    }
}
