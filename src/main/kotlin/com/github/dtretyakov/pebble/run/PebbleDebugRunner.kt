package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor

/**
 * Debug, the way pebble-tool debugs: for an Alloy app, a debug build installed the way Run installs,
 * with pebble-tool starting xsbug — Moddable's JavaScript debugger, in the SDK — and bridging the
 * watch to it; for a C app in the emulator, a debug build and `pebble gdb` in the console.
 *
 * Neither is the IDE's own debugger: that needs debug processes speaking xsbug's protocol and
 * GDB/MI, which is Phase 3. The SDK's gdb has no DAP to borrow.
 */
class PebbleDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "PebbleAlloyDebug"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID || profile !is PebbleRunConfiguration) return false
        return debugsWithXsbug(profile) || debugsWithGdb(profile)
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    companion object {
        /** An Alloy app, where Alloy runs: a watch runs whatever it is, the emulator is the one asked for. */
        fun debugsWithXsbug(configuration: PebbleRunConfiguration): Boolean =
            projectType(configuration) == PebbleProjectInfo.ProjectType.ALLOY &&
                (configuration.options.target != PebbleRunTarget.EMULATOR ||
                    PebblePlatform.of(configuration.platform().orEmpty())?.alloy != false)

        /** A C app in the emulator: `pebble gdb` needs QEMU's gdb stub, so a watch cannot be debugged. */
        fun debugsWithGdb(configuration: PebbleRunConfiguration): Boolean =
            projectType(configuration) == PebbleProjectInfo.ProjectType.NATIVE &&
                configuration.options.target == PebbleRunTarget.EMULATOR

        private fun projectType(configuration: PebbleRunConfiguration): PebbleProjectInfo.ProjectType? {
            val model = PebbleProject.getInstance(configuration.project)
            return model.primaryRoot()?.let { model.infoAt(it)?.projectType }
        }

        /** Whether a launch builds with `--debug`: asked for on the configuration, or launched with Debug. */
        fun debugBuild(configuration: PebbleRunConfiguration, executorId: String?): Boolean =
            configuration.options.debugBuild || executorId == DefaultDebugExecutor.EXECUTOR_ID
    }
}
