package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebbleSdkList
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import javax.swing.JComponent
import javax.swing.JList

/**
 * The installed SDKs, the active one marked, with Install, Remove and Activate — each one a
 * pebble-tool command, run in the background, after which the list reads the disk again.
 */
internal class SdkListPanel(private val project: Project?, parent: Disposable) {

    private val model = CollectionListModel<PebbleSdk>()
    private val list = JBList(model).apply {
        cellRenderer = object : SimpleListCellRenderer<PebbleSdk>() {
            override fun customize(list: JList<out PebbleSdk>, value: PebbleSdk?, index: Int, selected: Boolean, focused: Boolean) {
                value ?: return
                val active = value.version == PebbleSdkService.getInstance().currentSdk()?.version
                text = value.version + if (active) "  (active)" else ""
                icon = if (active) AllIcons.Actions.Checked else PebbleIcons.PEBBLE
            }
        }
        emptyText.text = "No SDKs installed"
    }

    val component: JComponent = ToolbarDecorator.createDecorator(list)
        .setAddAction { button -> chooseVersionToInstall(button.preferredPopupPoint.component as? JComponent ?: list) }
        .setAddActionName("Install SDK…")
        .setRemoveAction { remove() }
        .setRemoveActionName("Remove SDK")
        .addExtraAction(Activate())
        .disableUpDownActions()
        .setPreferredSize(java.awt.Dimension(0, 120))
        .createPanel()

    init {
        reload()
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
            PebbleSdkService.TOPIC,
            PebbleSdkService.Listener { ApplicationManager.getApplication().invokeLater(::reload, ModalityState.any()) },
        )
    }

    private fun reload() {
        val selected = list.selectedValue?.version
        model.replaceAll(PebbleSdkService.getInstance().sdks().installed)
        model.items.indexOfFirst { it.version == selected }.takeIf { it >= 0 }?.let(list::setSelectedIndex)
    }

    private fun chooseVersionToInstall(anchor: JComponent) {
        val available = ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<List<String>, RuntimeException> {
                PebbleSdkList.available(PebbleCommands.capture(listOf("sdk", "list")).orEmpty())
            },
            "Fetching Pebble SDK Versions",
            true,
            project,
        )
        if (available.isEmpty()) {
            Messages.showInfoMessage(anchor, "No other SDK versions are available, or the download server could not be reached.", "Install Pebble SDK")
            return
        }
        JBPopupFactory.getInstance()
            .createPopupChooserBuilder(available)
            .setTitle("Install Pebble SDK")
            .setItemChosenCallback { PebbleCommands.installSdk(project, it) }
            .createPopup()
            .showUnderneathOf(anchor)
    }

    private fun remove() {
        val sdk = list.selectedValue ?: return
        val answer = Messages.showYesNoDialog(
            list,
            "Remove Pebble SDK ${sdk.version}, with its toolchain and its emulators' saved state?",
            "Remove Pebble SDK",
            Messages.getQuestionIcon(),
        )
        if (answer == Messages.YES) PebbleCommands.uninstallSdk(project, sdk.version)
    }

    private inner class Activate : DumbAwareAction("Activate", "Makes the selected SDK the one pebble-tool uses", AllIcons.Actions.Checked) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            val selected = list.selectedValue
            e.presentation.isEnabled = selected != null && selected.version != PebbleSdkService.getInstance().currentSdk()?.version
        }

        override fun actionPerformed(e: AnActionEvent) {
            list.selectedValue?.let { PebbleCommands.activateSdk(project, it.version) }
        }
    }
}
