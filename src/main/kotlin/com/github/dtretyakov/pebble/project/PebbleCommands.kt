package com.github.dtretyakov.pebble.project

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.EditorNotifications
import java.nio.file.Path

/**
 * Running pebble-tool for the things that change the machine rather than a project: installing
 * and activating SDKs.
 *
 * In the background with a progress bar, because `pebble sdk install` downloads a few hundred
 * megabytes; and with the outcome as a notification carrying pebble-tool's own last words, because
 * when it fails its message is better than any the plugin could write about it.
 */
object PebbleCommands {

    const val NOTIFICATION_GROUP = "Pebble"

    fun installLatestSdk(project: Project?) =
        run(project, "Installing the Pebble SDK", listOf("sdk", "install", "latest"), "The Pebble SDK is installed.")

    fun activateNewestSdk(project: Project?) {
        val newest = PebbleSdkService.getInstance().sdks().installed.firstOrNull() ?: return
        run(project, "Activating Pebble SDK ${newest.version}", listOf("sdk", "activate", newest.version),
            "Pebble SDK ${newest.version} is active.")
    }

    private fun run(project: Project?, title: String, arguments: List<String>, success: String) {
        val tool = PebbleSdkService.getInstance().tool() ?: return
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val output = CapturingProcessHandler(command(tool, arguments))
                    .runProcessWithProgressIndicator(indicator)
                if (output.isCancelled) return

                val failed = output.exitCode != 0 || output.isTimeout
                val text = if (failed) {
                    val lastWords = (output.stderrLines + output.stdoutLines).takeLast(5).joinToString("<br>")
                    "`pebble ${arguments.joinToString(" ")}` failed.<br>$lastWords"
                } else {
                    success
                }
                NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
                    .createNotification(text, if (failed) NotificationType.ERROR else NotificationType.INFORMATION)
                    .notify(project)
            }

            override fun onFinished() {
                // The SDK changed under every open project, not only the one that asked.
                ProjectManager.getInstance().openProjects.forEach {
                    EditorNotifications.getInstance(it).updateAllNotifications()
                }
            }
        }.queue()
    }

    /**
     * A command line for pebble-tool with the user's shell environment, so the `node`, `npm` and
     * Python it runs are the ones the user's terminal would find.
     */
    fun command(tool: Path, arguments: List<String>, workDirectory: Path? = null): GeneralCommandLine =
        GeneralCommandLine(listOf(tool.toString()) + arguments)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(Charsets.UTF_8)
            .also { if (workDirectory != null) it.withWorkingDirectory(workDirectory) }
}
