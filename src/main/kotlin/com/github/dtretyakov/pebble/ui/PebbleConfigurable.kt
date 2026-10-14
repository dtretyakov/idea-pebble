package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.IdeCapabilities
import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.Clangd
import com.intellij.icons.AllIcons
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel

/**
 * Settings | Languages & Frameworks | Pebble: where pebble-tool is, and whether everything a build
 * needs is in place.
 *
 * The checklist is here rather than in a dialog of its own so that the problem and the control
 * that fixes it are on one page; Tools | Pebble | Check Environment opens this page.
 */
class PebbleConfigurable(private val project: Project) :
    BoundSearchableConfigurable("Pebble", "settings.pebble", ID) {

    override fun createPanel(): DialogPanel {
        val settings = PebbleAppSettings.getInstance()
        val items = PebbleEnvironment.check()

        return panel {
            group("pebble-tool") {
                row("Executable:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor()
                            .withTitle("Choose the pebble Executable"),
                        project,
                    )
                        .bindText(settings::pebbleToolPath)
                        .align(AlignX.FILL)
                        .comment("Leave empty to find it on the PATH or in ~/.local/bin, where uv installs it.")
                }
            }

            if (!IdeCapabilities.hasNativeC()) {
                group("C Code Insight") {
                    row("clangd:") {
                        textFieldWithBrowseButton(
                            FileChooserDescriptorFactory.createSingleFileNoJarsDescriptor().withTitle("Choose clangd"),
                            project,
                        )
                            .bindText(settings::clangdPath)
                            .align(AlignX.FILL)
                            .comment("This IDE has no C engine of its own; clangd, run through LSP4IJ, provides it. " +
                                "Leave empty to find it on the PATH.")
                    }
                }
            }

            if (PebbleSdkService.getInstance().tool() != null) {
                group("SDKs") {
                    row {
                        // The page's own disposable: the list listens for SDK changes while it is shown.
                        cell(SdkListPanel(project, disposable!!).component).align(Align.FILL)
                    }.resizableRow()
                }
            }

            group("Environment") {
                items.forEach { item ->
                    row(item.name) {
                        icon(
                            when {
                                item.status == PebbleEnvironment.Status.READY -> AllIcons.General.InspectionsOK
                                item.blocking -> AllIcons.General.Error
                                else -> AllIcons.General.Warning
                            },
                        )
                        text(item.detail)
                        when (item.fix) {
                            PebbleEnvironment.Fix.INSTALL_TOOL ->
                                browserLink("How to install", PebbleEnvironment.INSTALL_URL)
                            PebbleEnvironment.Fix.INSTALL_SDK ->
                                link("Install the latest SDK") { PebbleCommands.installLatestSdk(project) }
                            PebbleEnvironment.Fix.ACTIVATE_SDK ->
                                link("Activate the newest") { PebbleCommands.activateNewestSdk(project) }
                            PebbleEnvironment.Fix.INSTALL_LSP4IJ ->
                                link("Open Plugins") { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins") }
                            PebbleEnvironment.Fix.INSTALL_CLANGD -> browserLink("How to install", Clangd.INSTALL_URL)
                            null -> Unit
                        }
                    }
                }
                row {
                    link("Pebble developer documentation") { BrowserUtil.browse(DOCUMENTATION_URL) }
                }
            }
        }
    }

    companion object {
        const val ID = "settings.pebble"
        private const val DOCUMENTATION_URL = "https://developer.repebble.com/"
    }
}
