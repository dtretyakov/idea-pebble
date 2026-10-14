package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.github.dtretyakov.pebble.debug.AlloyDebugProcess
import com.github.dtretyakov.pebble.debug.XsbugServer
import com.github.dtretyakov.pebble.debug.gdb.GdbDebugProcess
import com.github.dtretyakov.pebble.debug.gdb.GdbSetup
import com.github.dtretyakov.pebble.emulator.EmulatorState
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.configurations.RunProfileState
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.GenericProgramRunner
import com.intellij.execution.runners.RunContentBuilder
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager

/**
 * Debug, in the IDE's debugger, with a debug build installed the way Run installs it.
 *
 * - An Alloy app: the plugin listens where xsbug would, so pebble-tool bridges the app's
 *   JavaScript engine to the IDE instead of launching xsbug ([AlloyDebugProcess]).
 * - A C app in the emulator: gdb, in MI mode, attached once the app is installed ([GdbDebugProcess]);
 *   or, with the advanced setting for it, `pebble gdb` in the console ([GdbSession]).
 */
class PebbleDebugRunner : GenericProgramRunner<RunnerSettings>() {

    override fun getRunnerId(): String = "PebbleAlloyDebug"

    override fun canRun(executorId: String, profile: RunProfile): Boolean {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID || profile !is PebbleRunConfiguration) return false
        return debugsWithXsbug(profile) || debugsWithGdb(profile)
    }

    override fun doExecute(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor? {
        val configuration = environment.runProfile as? PebbleRunConfiguration
        if (configuration != null && debugsWithXsbug(configuration)) return debugAlloy(state, environment)
        if (configuration != null && debugsWithGdb(configuration) && !GdbSession.inConsole()) return debugC(state, environment, configuration)
        val result = state.execute(environment.executor, this) ?: return null
        return RunContentBuilder(result, environment).showRunContent(environment.contentToReuse)
    }

    /**
     * The server first, so pebble-tool finds it and does not launch xsbug; then the install; then
     * the session, whose process logs out before pebble-tool is stopped.
     */
    private fun debugAlloy(state: RunProfileState, environment: ExecutionEnvironment): RunContentDescriptor {
        val server = try {
            XsbugServer.bind(listener = XsbugServer.UNATTENDED)
        } catch (_: XsbugServer.PortInUse) {
            throw ExecutionException(
                "Port ${XsbugServer.PORT} is in use: xsbug, or another Alloy debug session, is listening there. " +
                    "Close it and debug again.",
            )
        }
        val result = try {
            state.execute(environment.executor, this) ?: throw ExecutionException("Nothing to debug.")
        } catch (e: Throwable) {
            server.close()
            throw e
        }
        val session = XDebuggerManager.getInstance(environment.project).startSession(
            environment,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    val process = AlloyDebugProcess(session, server, result)
                    (result.processHandler as? PebbleLogProcessHandler)?.beforeDestroy = {
                        if (!process.logOut()) result.processHandler.notifyTextAvailable(AlloyDebugHint.TEXT, ProcessOutputTypes.SYSTEM)
                    }
                    return process
                }
            },
        )
        return session.runContentDescriptor
    }

    /** The install first; gdb attaches when it is done, and detaches before pebble-tool is stopped. */
    private fun debugC(state: RunProfileState, environment: ExecutionEnvironment, configuration: PebbleRunConfiguration): RunContentDescriptor {
        val root = PebbleProject.getInstance(environment.project).primaryRoot() ?: throw ExecutionException("No Pebble project here.")
        val platform = configuration.platform() ?: throw ExecutionException("The project targets no platform.")
        val result = state.execute(environment.executor, this) ?: throw ExecutionException("Nothing to debug.")
        val session = XDebuggerManager.getInstance(environment.project).startSession(
            environment,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    val process = GdbDebugProcess(session, result, setup = {
                        GdbSetup.prepare(PebbleSdkService.getInstance().sdks(), root, platform, EmulatorState.running())
                    })
                    (result.processHandler as? PebbleLogProcessHandler)?.beforeDestroy = { process.detach() }
                    return process
                }
            },
        )
        return session.runContentDescriptor
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
