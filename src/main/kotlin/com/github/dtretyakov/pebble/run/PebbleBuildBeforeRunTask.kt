package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.build.PebbleBuild
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Key
import javax.swing.Icon

class PebbleBuildBeforeRunTask : BeforeRunTask<PebbleBuildBeforeRunTask>(PebbleBuildBeforeRunTaskProvider.ID)

/**
 * "Build Pebble app" before launch: the platform's own place for a build that a run depends on.
 *
 * Added, enabled, to every Pebble run configuration, and removable like any other before-launch
 * task — someone installing a `.pbw` built elsewhere can turn it off. Runs on a pooled thread,
 * which is where a blocking build belongs.
 */
class PebbleBuildBeforeRunTaskProvider : BeforeRunTaskProvider<PebbleBuildBeforeRunTask>() {

    override fun getId(): Key<PebbleBuildBeforeRunTask> = ID

    override fun getName(): String = "Build Pebble app"

    override fun getIcon(): Icon = PebbleIcons.PEBBLE

    override fun createTask(runConfiguration: RunConfiguration): PebbleBuildBeforeRunTask? =
        if (runConfiguration is PebbleRunConfiguration) PebbleBuildBeforeRunTask().apply { isEnabled = true } else null

    override fun executeTask(
        context: DataContext,
        configuration: RunConfiguration,
        environment: ExecutionEnvironment,
        task: PebbleBuildBeforeRunTask,
    ): Boolean {
        val project = configuration.project
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return false
        // waf reads the files, not the editors.
        ApplicationManager.getApplication().invokeAndWait { FileDocumentManager.getInstance().saveAllDocuments() }
        val debug = (configuration as? PebbleRunConfiguration)?.options?.debugBuild == true
        return PebbleBuild.run(project, root, debug, indicator = null).succeeded
    }

    companion object {
        val ID: Key<PebbleBuildBeforeRunTask> = Key.create("Pebble.Build")
    }
}
