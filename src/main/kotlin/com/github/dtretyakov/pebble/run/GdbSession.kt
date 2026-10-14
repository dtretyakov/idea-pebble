package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import java.nio.file.Path

/**
 * Debug for a C app: gdb, attached to the emulator with the app's symbols, in the run's console.
 *
 * `pebble gdb` does the attaching — the firmware's debug ELF, `target remote` on QEMU's gdb stub,
 * the app's ELF at its load address — and the console, on a pseudo-terminal, is gdb's terminal:
 * the user types `break`, `continue` and `bt` into it. The IDE's own debugger would need a debug
 * process speaking GDB/MI, which is Phase 3; the SDK's gdb has no DAP to borrow.
 *
 * `pebble gdb` starts the app that is installed but does not install a new build, so the session
 * installs first: `install`, then `exec gdb`, in one shell so the console stays gdb's.
 */
object GdbSession {

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
