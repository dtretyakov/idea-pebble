package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.build.PebbleRelease
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction

/** Build | Check Pebble Release: a release build and what the appstore would say of it, in the Build window. */
class CheckReleaseAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        FileDocumentManager.getInstance().saveAllDocuments()
        object : Task.Backgroundable(project, "Checking Pebble release", true) {
            override fun run(indicator: ProgressIndicator) {
                PebbleRelease.check(project, root, indicator)
            }
        }.queue()
    }
}
