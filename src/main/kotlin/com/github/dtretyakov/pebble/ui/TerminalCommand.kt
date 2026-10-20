package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.ToolHosts
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection
import java.nio.file.Path

/**
 * Runs a command that needs the user — `sudo apt install …`, `brew install …` — in the IDE's
 * terminal, where they see it, type their password, and watch it finish.
 *
 * The plugin never runs these itself: a password prompt has nowhere to go in a background task,
 * and what goes onto the machine is the user's decision. Where the IDE has no terminal (it is a
 * plugin, and can be turned off), the command goes to the clipboard instead.
 *
 * [inToolHost]: in the shell of the machine the tools run on — on Windows, a tab that opens in the
 * WSL distribution, since `sudo apt install` means nothing to PowerShell. Off for the one command
 * that is Windows' own, installing WSL.
 */
internal object TerminalCommand {

    fun run(project: Project?, command: String, directory: Path? = null, title: String = "Pebble setup", inToolHost: Boolean = true) {
        val wsl = if (inToolHost) ToolHosts.current() as? WslToolHost else null
        if (project != null && hasTerminal()) {
            val start = directory?.toString() ?: project.basePath
            val commands = if (wsl != null) {
                val inside = directory?.let(wsl::toolPath) ?: project.basePath?.let { runCatching { Path.of(it) }.getOrNull() }?.let(wsl::toolPath) ?: "~"
                inWsl(wsl.distribution, inside, command)
            } else {
                listOf(command)
            }
            // In a class of its own: it is loaded only when the terminal plugin is there to link to.
            TerminalSession.run(project, commands, start, title)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        val where = if (wsl != null) "a terminal of the WSL distribution ${wsl.distribution} (<code>wsl -d ${wsl.distribution}</code>)" else "a terminal"
        NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
            .createNotification(
                "Copied to the clipboard",
                "Run it in $where: <code>${PebbleEnvironment.html(command)}</code>",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    /**
     * What to type into the user's shell on Windows — PowerShell, cmd or Git Bash — for [command]
     * to run in [distribution], in [directory]: one line, `wsl -d … -e bash -lc "cd '…' && …"`,
     * which all three read alike while the command has nothing in it any of them would expand. The
     * directory is changed to inside rather than with `--cd`, which takes no `\\wsl.localhost` path
     * and whose Linux path Git Bash would rewrite as one of its own. Otherwise: the distribution's
     * shell first, and the command typed into it.
     */
    internal fun inWsl(distribution: String, directory: String, command: String): List<String> {
        val cd = if (directory == "~") "cd" else "cd " + WslToolHost.shellQuote(directory)
        val inside = "$cd && $command"
        if (inside.none { it in UNSAFE }) return listOf("wsl -d $distribution -e bash -lc \"$inside\"")
        return listOf("wsl -d $distribution", inside)
    }

    /** What PowerShell, cmd or Git Bash would expand inside double quotes. */
    private const val UNSAFE = "\"$`%!^"

    private fun hasTerminal(): Boolean {
        val id = PluginId.getId(TERMINAL_PLUGIN)
        return PluginManagerCore.isPluginInstalled(id) && !PluginManagerCore.isDisabled(id)
    }

    private const val TERMINAL_PLUGIN = "org.jetbrains.plugins.terminal"
}
