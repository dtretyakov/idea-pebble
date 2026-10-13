package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.util.function.Function
import javax.swing.JComponent

/**
 * Says so, in the editor of any file inside a Pebble project, when nothing could be built.
 *
 * Without it the failure is invisible until the first build, and the first build is exactly the
 * moment a newcomer is least able to read a waf stack trace. Two links at most — the one that
 * fetches the missing thing and the settings page — and a close button that is remembered.
 */
class MissingSdkNotification : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        if (PropertiesComponent.getInstance(project).getBoolean(DISMISSED)) return null
        if (PebbleProject.getInstance(project).rootFor(file) == null) return null

        val problem = PebbleEnvironment.firstProblem(PebbleEnvironment.check()) ?: return null

        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Error).apply {
                text = "${problem.name}: ${problem.detail}"
                when (problem.fix) {
                    PebbleEnvironment.Fix.INSTALL_TOOL ->
                        createActionLabel("How to install") { BrowserUtil.browse(PebbleEnvironment.INSTALL_URL) }
                    PebbleEnvironment.Fix.INSTALL_SDK ->
                        createActionLabel("Install the latest SDK") { PebbleCommands.installLatestSdk(project) }
                    PebbleEnvironment.Fix.ACTIVATE_SDK ->
                        createActionLabel("Activate the newest SDK") { PebbleCommands.activateNewestSdk(project) }
                    null -> Unit
                }
                createActionLabel("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PebbleConfigurable::class.java)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
                setCloseAction {
                    PropertiesComponent.getInstance(project).setValue(DISMISSED, true)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
            }
        }
    }

    private companion object {
        const val DISMISSED = "pebble.missingSdkNotification.dismissed"
    }
}
