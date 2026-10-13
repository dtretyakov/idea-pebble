package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.build.PebbleBuild
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction

/** Build | Build Pebble App: `pebble build`, into the Build tool window. */
class BuildAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        // What is on screen is what gets built: waf reads the files, not the editor.
        FileDocumentManager.getInstance().saveAllDocuments()
        object : Task.Backgroundable(project, "Building Pebble app", true) {
            override fun run(indicator: ProgressIndicator) {
                PebbleBuild.run(project, root, debug = false, indicator = indicator)
            }
        }.queue()
    }
}
