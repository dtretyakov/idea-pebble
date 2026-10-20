package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.emulator.EmulatorToolWindowFactory
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunConfiguration
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.intellij.execution.RunManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.guessProjectDir
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tools | Pebble | Take Screenshot: from wherever the selected run configuration installs — the
 * emulator's frame is already in the IDE, a watch's is fetched with `pebble screenshot`.
 */
class TakeScreenshotAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val configuration = RunManager.getInstance(project).selectedConfiguration?.configuration as? PebbleRunConfiguration
        val options = configuration?.options
        if (options == null || options.target == PebbleRunTarget.EMULATOR) {
            // The emulator's screen is already here; its window has the button.
            EmulatorToolWindowFactory.reveal(project)
            return
        }

        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        val name = "watch-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.png"
        val file = FileChooserFactory.getInstance()
            .createSaveFileDialog(FileSaverDescriptor("Save Screenshot", "", "png"), project)
            .save(project.guessProjectDir(), name)
            ?.file
            ?: return

        // Where pebble-tool writes it, as pebble-tool spells it: under WSL, `/mnt/c/…` or `/home/…`.
        val spelled = PebbleSdkService.getInstance().host()?.toolPath(file.toPath()) ?: run {
            NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
                .createNotification("pebble-tool cannot write to ${file.absolutePath}: choose a folder on a Windows drive or in its WSL distribution.", NotificationType.ERROR)
                .notify(project)
            return
        }
        val arguments = listOf("screenshot", "--no-open") +
            PebbleInstallCommand.connection(options.target, configuration.platform(), options.phoneAddress, options.adbSerial) +
            spelled
        if (PebbleSdkService.getInstance().tool() == null) {
            NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
                .createNotification("pebble-tool is not installed.", NotificationType.ERROR)
                .notify(project)
            return
        }
        PebbleCommands.run(project, "Taking a screenshot of the watch", arguments, "Saved ${file.name}.", workDirectory = root)
    }
}
