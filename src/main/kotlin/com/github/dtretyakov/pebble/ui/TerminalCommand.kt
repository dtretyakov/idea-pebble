package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import java.awt.datatransfer.StringSelection

/**
 * Runs a command that needs the user — `sudo apt install …`, `brew install …` — in the IDE's
 * terminal, where they see it, type their password, and watch it finish.
 *
 * The plugin never runs these itself: a password prompt has nowhere to go in a background task,
 * and what goes onto the machine is the user's decision. Where the IDE has no terminal (it is a
 * plugin, and can be turned off), the command goes to the clipboard instead.
 */
internal object TerminalCommand {

    fun run(project: Project?, command: String) {
        if (project != null && hasTerminal()) {
            // In a class of its own: it is loaded only when the terminal plugin is there to link to.
            TerminalSession.run(project, command)
            return
        }
        CopyPasteManager.getInstance().setContents(StringSelection(command))
        NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
            .createNotification(
                "Copied to the clipboard",
                "Run it in a terminal: <code>${PebbleEnvironment.html(command)}</code>",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }

    private fun hasTerminal(): Boolean {
        val id = PluginId.getId(TERMINAL_PLUGIN)
        return PluginManagerCore.isPluginInstalled(id) && !PluginManagerCore.isDisabled(id)
    }

    private const val TERMINAL_PLUGIN = "org.jetbrains.plugins.terminal"
}
