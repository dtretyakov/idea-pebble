package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.build.PebbleBuild
import com.github.dtretyakov.pebble.emulator.EmulatorWarmUp
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.ui.PebbleIcons
import com.intellij.execution.BeforeRunTask
import com.intellij.execution.BeforeRunTaskProvider
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebuggerManager
import java.nio.file.Path
import java.util.concurrent.Future
import com.intellij.openapi.util.Key
import javax.swing.Icon

class PebbleBuildBeforeRunTask : BeforeRunTask<PebbleBuildBeforeRunTask>(PebbleBuildBeforeRunTaskProvider.ID)

/**
 * "Build Pebble app" before launch: the platform's own place for a build that a run depends on —
 * and, for a run on the emulator, for starting it.
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
        // pebble-tool installs whatever the last build was, debug or not, so Debug has to build for it.
        val debug = (configuration as? PebbleRunConfiguration)?.let { PebbleDebugRunner.debugBuild(it, environment.executor.id) } == true
        // The emulator is seen to while the app builds: asking it costs a second and a half, and
        // a build is longer than that.
        val emulator = startEmulator(configuration, root, ProgressManager.getInstance().progressIndicator)
        val built = PebbleBuild.run(project, root, debug, indicator = null).succeeded
        // A failed build installs nothing: the emulator carries on coming up, but the run does not wait for it.
        if (built) emulator?.let { runCatching { it.get() } }
        return built
    }

    /**
     * And the emulator the run installs on, while there is still a background thread to wait on:
     * `pebble install` gives a starting one only five seconds, and finds a silent one no better.
     * Never fails the run — install says what is wrong if the emulator did not come up. See
     * [EmulatorWarmUp].
     */
    private fun startEmulator(configuration: RunConfiguration, root: Path, indicator: ProgressIndicator?): Future<*>? {
        val run = configuration as? PebbleRunConfiguration ?: return null
        if (run.options.target != PebbleRunTarget.EMULATOR) return null
        val platform = run.platform() ?: return null
        val tool = PebbleSdkService.getInstance().tool() ?: return null
        val version = PebbleSdkService.getInstance().currentSdk()?.version
        val debugging = debugging(run.project)
        return AppExecutorUtil.getAppExecutorService().submit {
            EmulatorWarmUp.ensureUp(tool, root, platform, version, indicator, debugging)
        }
    }

    /** A Pebble app being debugged: its watch may be stopped at a breakpoint, silent for that. */
    private fun debugging(project: Project): Boolean =
        XDebuggerManager.getInstance(project).debugSessions.any { session ->
            !session.isStopped && session.runProfile is PebbleRunConfiguration
        }

    companion object {
        val ID: Key<PebbleBuildBeforeRunTask> = Key.create("Pebble.Build")
    }
}
