package com.github.dtretyakov.pebble.project

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
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
        activateSdk(project, newest.version)
    }

    fun activateSdk(project: Project?, version: String) =
        run(project, "Activating Pebble SDK $version", listOf("sdk", "activate", version), "Pebble SDK $version is active.")

    fun installSdk(project: Project?, version: String) =
        run(project, "Installing Pebble SDK $version", listOf("sdk", "install", version), "Pebble SDK $version is installed.")

    fun uninstallSdk(project: Project?, version: String) =
        run(project, "Removing Pebble SDK $version", listOf("sdk", "uninstall", version), "Pebble SDK $version is removed.")

    /** `pebble [arguments]`'s output, waited for; null when it could not run or failed. */
    fun capture(arguments: List<String>): String? {
        val tool = PebbleSdkService.getInstance().tool() ?: return null
        val output = CapturingProcessHandler(command(tool, arguments)).runProcess(CAPTURE_TIMEOUT_MILLIS)
        return output.stdout.takeIf { output.exitCode == 0 && !output.isTimeout }
    }

    private const val CAPTURE_TIMEOUT_MILLIS = 60_000

    /**
     * Runs `pebble [arguments]` in the background. False when there is no pebble-tool to run.
     * [quiet] keeps a success to itself: only a failure is worth a notification.
     */
    fun run(
        project: Project?,
        title: String,
        arguments: List<String>,
        success: String,
        quiet: Boolean = false,
        workDirectory: Path? = null,
    ): Boolean {
        val tool = PebbleSdkService.getInstance().tool() ?: return false
        object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                val output = CapturingProcessHandler(command(tool, arguments, workDirectory))
                    .runProcessWithProgressIndicator(indicator)
                if (output.isCancelled) return

                val failed = output.exitCode != 0 || output.isTimeout
                if (!failed && quiet) return
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
                if (arguments.firstOrNull() != "sdk") return
                // The SDK changed under every open project, not only the one that asked.
                ApplicationManager.getApplication().messageBus.syncPublisher(PebbleSdkService.TOPIC).sdksChanged()
            }
        }.queue()
        return true
    }

    /**
     * A command line for pebble-tool with the user's shell environment, so the `node`, `npm` and
     * Python it runs are the ones the user's terminal would find.
     *
     * `PYTHONWARNINGS=ignore` because on Python 3.12 and later libpebble2's docstrings produce a
     * screenful of `SyntaxWarning`s before every command, which would bury its real output.
     */
    fun command(tool: Path, arguments: List<String>, workDirectory: Path? = null): GeneralCommandLine =
        GeneralCommandLine(listOf(tool.toString()) + arguments)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("PYTHONWARNINGS", "ignore")
            .withCharset(Charsets.UTF_8)
            .also { if (workDirectory != null) it.withWorkingDirectory(workDirectory) }
}
