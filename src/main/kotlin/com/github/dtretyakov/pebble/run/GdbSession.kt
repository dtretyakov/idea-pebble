package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.options.advanced.AdvancedSettings
import java.nio.file.Path

/**
 * Debug for a C app in gdb's own console, where the advanced setting asks for it; otherwise C is
 * debugged in the IDE's debugger ([com.github.dtretyakov.pebble.debug.gdb.GdbDebugProcess]).
 *
 * `pebble gdb` does the attaching — the firmware's debug ELF, `target remote` on QEMU's gdb stub,
 * the app's ELF at its load address — and the console, on a pseudo-terminal, is gdb's terminal:
 * the user types `break`, `continue` and `bt` into it.
 *
 * `pebble gdb` starts the app that is installed but does not install a new build, so the session
 * installs first: `install`, then `exec gdb`, in one shell so the console stays gdb's.
 */
object GdbSession {

    const val SETTING = "pebble.debug.gdbConsole"

    /** Whether Debug opens gdb's console rather than the IDE's debugger, for a C app. */
    fun inConsole(): Boolean = AdvancedSettings.getBoolean(SETTING)

    const val HINT = "gdb, attached to the emulator with the app's symbols. Set breakpoints with " +
        "`break main.c:42` or `break my_click_handler`, then `continue`, and use the watch's buttons to reach them. " +
        "`bt`, `info locals` and `print` work as usual. Stop ends the session.\n\n"

    fun arguments(platform: String): Pair<List<String>, List<String>> {
        val connection = PebbleInstallCommand.connection(PebbleRunTarget.EMULATOR, platform, phoneAddress = "", adbSerial = "")
        return (listOf("install") + connection) to (listOf("gdb") + connection)
    }

    /** `pebble install …; exec pebble gdb …` on a pseudo-terminal, quoted for `sh`. */
    fun commandLine(tool: Path, platform: String, root: Path): GeneralCommandLine {
        val (install, gdb) = arguments(platform)
        val script = "${shell(listOf(tool.toString()) + install)} && exec ${shell(listOf(tool.toString()) + gdb)}"
        val base = PebbleCommands.command(tool, emptyList(), root)
        return PtyCommandLine(
            GeneralCommandLine("/bin/sh", "-c", script)
                .withEnvironment(base.environment)
                .withParentEnvironmentType(base.parentEnvironmentType)
                .withWorkingDirectory(root)
                .withCharset(Charsets.UTF_8),
        ).withInitialColumns(PtyCommandLine.MAX_COLUMNS).withConsoleMode(false)
    }

    /** Says what the console is, before gdb says anything. */
    fun attachHint(handler: ProcessHandler) {
        handler.addProcessListener(
            object : ProcessListener {
                override fun startNotified(event: ProcessEvent) {
                    handler.notifyTextAvailable(HINT, ProcessOutputTypes.SYSTEM)
                }
            },
        )
    }

    internal fun shell(words: List<String>): String = words.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
}
