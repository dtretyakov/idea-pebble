package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * The Pebble Emulator tool window: the emulator's screen, live, with its buttons.
 *
 * Available in Pebble projects only, and watching for an emulator only while it is visible.
 */
class EmulatorToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean = PebbleProject.getInstance(project).isPebbleProject()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EmulatorPanel(project, toolWindow.disposable)
        val content = SimpleToolWindowPanel(true, true).apply {
            setContent(panel)
            toolbar = ActionManager.getInstance()
                .createActionToolbar(
                    "PebbleEmulator",
                    DefaultActionGroup(SaveScreenshot(project, panel), EmulatorControlActions(project), StopEmulator(project)),
                    true,
                )
                .also { it.targetComponent = panel }
                .component
        }
        toolWindow.contentManager.addContent(toolWindow.contentManager.factory.createContent(content, null, false))

        val session = EmulatorSession.getInstance(project)
        if (toolWindow.isVisible) session.startWatching()
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(manager: ToolWindowManager) {
                    if (manager.getToolWindow(ID)?.isVisible == true) session.startWatching() else session.stopWatching()
                }
            },
        )
    }

    /** The watch's own pixels, as a PNG named for the platform and the moment. */
    private class SaveScreenshot(private val project: Project, private val panel: EmulatorPanel) :
        DumbAwareAction("Save Screenshot", "Saves the emulator's screen as a PNG", AllIcons.Actions.MenuSaveall) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = EmulatorSession.getInstance(project).lastFrame != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val image = panel.screenshot() ?: return
            val platform = EmulatorSession.getInstance(project).emulator?.platform ?: "pebble"
            val name = "$platform-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
            val target = FileChooserFactory.getInstance()
                .createSaveFileDialog(FileSaverDescriptor("Save Screenshot", "", "png"), project)
                .save(project.guessProjectDir(), name)
                ?: return
            ImageIO.write(image, "png", target.file)
        }
    }

    /** `pebble kill`: every emulator pebble-tool started, the way it would stop them itself. */
    private class StopEmulator(private val project: Project) :
        DumbAwareAction("Stop Emulator", "Stops the emulator with pebble kill", AllIcons.Actions.Suspend) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = EmulatorSession.getInstance(project).emulator != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!PebbleCommands.run(project, "Stopping the Pebble emulator", listOf("kill"), "The emulator is stopped.", quiet = true)) {
                NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
                    .createNotification("pebble-tool is not installed.", NotificationType.ERROR)
                    .notify(project)
            }
        }
    }

    companion object {
        const val ID = "Pebble Emulator"

        /** Brings the window up without taking focus from the editor — after a run starts an emulator. */
        fun reveal(project: Project) {
            ToolWindowManager.getInstance(project).invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
                EmulatorSession.getInstance(project).lookNow()
            }
        }
    }
}
