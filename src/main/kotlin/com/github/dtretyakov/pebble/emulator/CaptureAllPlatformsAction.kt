package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * A screenshot of the app on every platform it targets, with pebble-tool's own
 * `screenshot --all-platforms`: one emulator after another, the clock at 10:10, the files in
 * `screenshots/`. Then the Platforms tab, to compare them.
 *
 * pebble-tool starts those emulators without VNC, and an emulator started with VNC is restarted
 * without it — so the one on screen stops. That is said before, not discovered after.
 */
class CaptureAllPlatformsAction : DumbAwareAction(
    "Capture Every Platform",
    "Screenshots of the app on every platform it targets, side by side",
    AllIcons.Actions.ShowAsTree,
) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        val tool = PebbleSdkService.getInstance().tool() ?: return notify(project, "pebble-tool is not installed.", NotificationType.ERROR)
        if (EmulatorSession.getInstance(project).emulator != null) {
            val answer = Messages.showOkCancelDialog(
                project,
                "pebble-tool starts an emulator for each platform in turn, and stops the one shown here to do it.",
                "Capture Every Platform",
                "Capture",
                Messages.getCancelButton(),
                Messages.getInformationIcon(),
            )
            if (answer != Messages.OK) return
        }
        FileDocumentManager.getInstance().saveAllDocuments()
        capture(project, tool, root)
    }

    private fun capture(project: Project, tool: Path, root: Path) {
        object : Task.Backgroundable(project, "Capturing every platform", true) {
            private var failed = ""

            override fun run(indicator: ProgressIndicator) {
                val handler = OSProcessHandler(PebbleCommands.command(tool, listOf("screenshot", "--all-platforms", "--no-open"), root))
                val output = StringBuilder()
                handler.addProcessListener(
                    object : ProcessListener {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            if (outputType == ProcessOutputTypes.SYSTEM) return
                            output.append(event.text)
                            CAPTURING.find(event.text)?.let { indicator.text = "Capturing ${it.groupValues[1]}" }
                        }
                    },
                )
                handler.startNotify()
                while (!handler.waitFor(200)) if (indicator.isCanceled) handler.destroyProcess()
                if (handler.exitCode != 0 && !indicator.isCanceled) {
                    failed = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                }
                LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve(PlatformScreenshots.DIRECTORY)), false, true, null)
            }

            override fun onSuccess() {
                if (failed.isNotEmpty()) notify(project, "Capturing every platform failed: $failed", NotificationType.ERROR)
                EmulatorToolWindowFactory.showPlatforms(project)
            }
        }.queue()
    }

    private fun notify(project: Project, text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
            .createNotification(text, type)
            .notify(project)
    }

    private companion object {
        val CAPTURING = Regex("""=== Capturing (\S+) ===""")
    }
}
