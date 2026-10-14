package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.task.ModuleBuildTask
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.TaskRunnerResults
import org.jetbrains.concurrency.AsyncPromise
import org.jetbrains.concurrency.Promise
import java.nio.file.Path

/**
 * Build Project for a Pebble app: `pebble build` ([PebbleBuild]) for every module whose content
 * holds a Pebble project, so Build | Build Project, Ctrl+F9 and anything else that builds through
 * the IDE's project tasks — the IDE's MCP server's `build_project` among them — build it, with
 * the diagnostics in the Build tool window where they all look for them.
 */
class PebbleProjectTaskRunner : ProjectTaskRunner() {

    override fun canRun(projectTask: ProjectTask): Boolean = false

    override fun canRun(project: Project, projectTask: ProjectTask): Boolean =
        projectTask is ModuleBuildTask && rootOf(projectTask.module) != null

    override fun run(project: Project, context: ProjectTaskContext, vararg tasks: ProjectTask): Promise<Result> {
        val roots = tasks.filterIsInstance<ModuleBuildTask>().mapNotNull { rootOf(it.module) }.distinct()
        val promise = AsyncPromise<Result>()
        // Called off the UI thread, possibly under a read lock: the documents are saved, and the
        // build started, from the UI thread once it is free. waf reads the files, not the editors.
        ApplicationManager.getApplication().invokeLater(
            {
                FileDocumentManager.getInstance().saveAllDocuments()
                object : Task.Backgroundable(project, "Building Pebble app", true) {
                    override fun run(indicator: ProgressIndicator) {
                        val results = roots.map { PebbleBuild.run(project, it, debug = false, indicator = indicator) }
                        promise.setResult(
                            when {
                                indicator.isCanceled -> TaskRunnerResults.ABORTED
                                results.all { it.succeeded } -> TaskRunnerResults.SUCCESS
                                else -> TaskRunnerResults.FAILURE
                            },
                        )
                    }

                    override fun onCancel() {
                        if (!promise.isDone) promise.setResult(TaskRunnerResults.ABORTED)
                    }

                    override fun onThrowable(error: Throwable) {
                        if (!promise.isDone) promise.setError(error)
                    }
                }.queue()
            },
            ModalityState.nonModal(),
            project.disposed,
        )
        return promise
    }

    companion object {
        /** The Pebble project in [module]'s content, if it has one. */
        fun rootOf(module: Module): Path? {
            val roots = PebbleProject.getInstance(module.project).roots()
            val content = ModuleRootManager.getInstance(module).contentRoots.mapNotNull { it.toNioPathOrNull() }
            return roots.firstOrNull { root -> content.any { root.startsWith(it) } }
        }
    }
}
