package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.build.PebblePublish
import com.github.dtretyakov.pebble.build.PebbleRelease
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * Build | Publish to Pebble Appstore: Check Pebble Release first, and with nothing in it the
 * appstore would refuse, `pebble publish` in the IDE's terminal — which logs in, asks for what a
 * new app's listing needs and where its screenshots come from, and uploads.
 */
class PublishAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        val sdk = PebbleSdkService.getInstance()
        val tool = sdk.tool() ?: return PebbleCommands.notify(project, "pebble-tool is not installed: Set Up Pebble SDK installs it.", NotificationType.ERROR)
        FileDocumentManager.getInstance().saveAllDocuments()
        object : Task.Backgroundable(project, "Checking Pebble release before publishing", true) {
            private var refused = 0

            override fun run(indicator: ProgressIndicator) {
                refused = PebbleRelease.check(project, root, indicator).count { it.severity == "error" }
            }

            override fun onSuccess() {
                if (refused > 0) {
                    PebbleCommands.notify(project, "Not published: $refused problem(s) the appstore would refuse, in the Build window.", NotificationType.ERROR)
                    return
                }
                val spelled = sdk.host()?.toolPath(tool) ?: tool.toString()
                TerminalCommand.run(project, PebblePublish.command(spelled, PebblePublish.loggedIn(sdk.dataDir())), root, "Pebble publish")
            }
        }.queue()
    }

}
