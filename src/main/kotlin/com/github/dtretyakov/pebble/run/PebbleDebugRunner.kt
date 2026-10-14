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
 * Debug for an Alloy app: a debug build, installed the way Run installs, with pebble-tool starting
 * xsbug — Moddable's JavaScript debugger, in the SDK — and bridging the watch to it.
 *
 * The debugging itself happens in xsbug, not in the IDE's debugger: that needs a debug process
 * speaking xsbug's protocol, which is Phase 3. C is not offered Debug at all, since the SDK's gdb
 * has no debug adapter; `pebble gdb` in a terminal is what there is.
 */
class PebbleDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "PebbleAlloyDebug"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID || profile !is PebbleRunConfiguration) return false
        val model = PebbleProject.getInstance(profile.project)
        val root = model.primaryRoot() ?: return false
        if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return false
        // A watch runs whatever platform it is; the emulator is the one asked for.
        return profile.options.target != PebbleRunTarget.EMULATOR || PebblePlatform.of(profile.platform().orEmpty())?.alloy != false
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    companion object {
        /** Whether a launch builds with `--debug`: asked for on the configuration, or launched with Debug. */
        fun debugBuild(configuration: PebbleRunConfiguration, executorId: String?): Boolean =
            configuration.options.debugBuild || executorId == DefaultDebugExecutor.EXECUTOR_ID
    }
}
