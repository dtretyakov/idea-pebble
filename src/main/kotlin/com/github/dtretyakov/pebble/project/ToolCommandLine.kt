package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.HostCommand
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import java.nio.file.Path

/**
 * The one place a command line for a tool is made — pebble-tool, the SDK's gdb and emulator, uv,
 * clangd — so that each runs where the tools are ([ToolHosts]): as it is on a Mac or Linux, through
 * `wsl.exe` on Windows.
 *
 * Arguments that are paths must already be in the tools' spelling ([ToolHost.toolPath]): only the
 * executable and the working directory are translated here.
 */
object ToolCommandLine {

    /**
     * `PYTHONWARNINGS=ignore` because on Python 3.12 and later libpebble2's docstrings produce a
     * screenful of `SyntaxWarning`s before every pebble-tool command, which would bury its output.
     */
    val PEBBLE_ENVIRONMENT: Map<String, String> = mapOf("PYTHONWARNINGS" to "ignore")

    fun host(): ToolHost = ToolHosts.forCommands()

    /**
     * With the user's shell environment, so the `node`, `npm` and Python a tool runs are the ones
     * the user's terminal would find.
     */
    fun build(
        executable: Path,
        arguments: List<String>,
        workDirectory: Path? = null,
        environment: Map<String, String> = emptyMap(),
        host: ToolHost = host(),
    ): GeneralCommandLine = from { host.command(executable, arguments, workDirectory, environment) }

    /** A POSIX [script] for the tools' `/bin/sh`. */
    fun shell(script: String, workDirectory: Path?, environment: Map<String, String> = emptyMap(), host: ToolHost = host()): GeneralCommandLine =
        from { host.shell(script, workDirectory, environment) }

    private fun from(make: () -> HostCommand): GeneralCommandLine {
        val command = try {
            make()
        } catch (e: IllegalArgumentException) {
            throw ExecutionException(e.message ?: e.toString())
        }
        return from(command)
    }

    /** A command the host has made, as the IDE starts it. */
    fun from(command: HostCommand): GeneralCommandLine {
        return GeneralCommandLine(command.words)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment(command.environment)
            .withCharset(Charsets.UTF_8)
            .also { line -> command.workDirectory?.let { line.withWorkingDirectory(it) } }
    }
}
