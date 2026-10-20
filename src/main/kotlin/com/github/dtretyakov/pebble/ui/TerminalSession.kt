package com.github.dtretyakov.pebble.ui

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** The terminal plugin's side of [TerminalCommand]: only loaded where that plugin is. */
internal object TerminalSession {

    /**
     * A new tab of the user's own shell, with [commands] typed into it one after another.
     *
     * Deprecated in 2026.2 for `TerminalToolWindowTabsManager`, which is experimental and lives in
     * the terminal's frontend module — a dependency this plugin cannot rely on back to 2026.1,
     * where `sinceBuild` promises it runs. Move when that API settles. Its overload that starts a
     * tab with another shell is internal, which is why a WSL command is typed rather than started.
     */
    @Suppress("DEPRECATION")
    fun run(project: Project, commands: List<String>, directory: String?, title: String) {
        val widget = TerminalToolWindowManager.getInstance(project).createShellWidget(directory, title, true, true)
        commands.forEach(widget::sendCommandToExecute)
    }
}
