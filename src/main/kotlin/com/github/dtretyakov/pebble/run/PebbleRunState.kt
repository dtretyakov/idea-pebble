package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.emulator.EmulatorToolWindowFactory
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.CommandLineState
import com.intellij.execution.configurations.PtyCommandLine
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
        val arguments = PebbleInstallCommand.arguments(
            options.target,
            configuration.platform(),
            options.phoneAddress,
            options.adbSerial,
            options.logs,
        )

        consoleBuilder.addFilter(PebbleLogFilter(project, root))
        val handler = processHandler(tool, arguments, root).also { ProcessTerminatedListener.attach(it) }
        // The emulator's screen is in the IDE, not in a window of its own: bring it up.
        if (options.target == PebbleRunTarget.EMULATOR) EmulatorToolWindowFactory.reveal(project)
        return handler
    }

    companion object {
        /** pebble-tool on a pseudo-terminal, with a handler that turns its colours into console colours. */
        fun processHandler(tool: Path, arguments: List<String>, root: Path): ProcessHandler =
            KillableColoredProcessHandler(
                PtyCommandLine(PebbleCommands.command(tool, arguments, root))
                    .withInitialColumns(PtyCommandLine.MAX_COLUMNS)
                    .withConsoleMode(false),
            )
    }
}
