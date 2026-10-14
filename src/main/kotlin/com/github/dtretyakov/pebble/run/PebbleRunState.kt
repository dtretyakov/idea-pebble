package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.emulator.EmulatorToolWindowFactory
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.execution.runners.ExecutionEnvironment
import java.nio.file.Path

/**
 * `pebble install`, and with logs on, the app's logs for as long as the run lasts.
 *
 * On a pseudo-terminal, because pebble-tool shows a log line's level only as a colour and only
 * when it is writing to a terminal; the console renders the colours, so an error reads as one.
 * Stopping the run stops the logs and leaves the emulator up, so the next run starts in seconds.
 *
 * Debug for Alloy is the same install, with logs whatever the configuration says — the watch keeps
 * the engine's debugger connection only while its log is being read — and the IDE's debugger in
 * xsbug's place ([PebbleDebugRunner]); for C, a gdb session in the console ([GdbSession]).
 */
class PebbleRunState(environment: ExecutionEnvironment, private val configuration: PebbleRunConfiguration) :
    CommandLineState(environment) {

    override fun startProcess(): ProcessHandler {
        val project = configuration.project
        val root = PebbleProject.getInstance(project).primaryRoot()
            ?: throw ExecutionException("No Pebble project here.")
        val tool = PebbleSdkService.getInstance().tool()
            ?: throw ExecutionException("pebble-tool is not installed.")

        val options = configuration.options
        val debug = environment.executor.id == DefaultDebugExecutor.EXECUTOR_ID
        val debugAlloy = debug && PebbleDebugRunner.debugsWithXsbug(configuration)
        val arguments = PebbleInstallCommand.arguments(
            options.target,
            configuration.platform(),
            options.phoneAddress,
            options.adbSerial,
            options.logs || debugAlloy,
        )

        consoleBuilder.addFilter(PebbleLogFilter(project, root))
        if (debug && PebbleDebugRunner.debugsWithGdb(configuration)) {
            val platform = configuration.platform() ?: throw ExecutionException("The project targets no platform.")
            val gdb = KillableColoredProcessHandler(GdbSession.commandLine(tool, platform, root))
            ProcessTerminatedListener.attach(gdb)
            GdbSession.attachHint(gdb)
            EmulatorToolWindowFactory.reveal(project)
            return gdb
        }
        val handler = processHandler(tool, arguments, root, options.minimumLogLevel).also { ProcessTerminatedListener.attach(it) }
        ConnectionHint.attach(handler, options.target, options.phoneAddress)
        HeapLeakHint.attach(handler)
        // The emulator's screen is in the IDE, not in a window of its own: bring it up.
        if (options.target == PebbleRunTarget.EMULATOR) EmulatorToolWindowFactory.reveal(project)
        return handler
    }

    companion object {
        /**
         * pebble-tool on a pseudo-terminal, with a handler that turns its colours into console
         * colours and leaves out the log lines below [minimum].
         */
        fun processHandler(tool: Path, arguments: List<String>, root: Path, minimum: LogLevel = LogLevel.VERBOSE): ProcessHandler =
            PebbleLogProcessHandler(
                PtyCommandLine(PebbleCommands.command(tool, arguments, root))
                    .withInitialColumns(PtyCommandLine.MAX_COLUMNS)
                    .withConsoleMode(false),
                minimum,
            )
    }
}
