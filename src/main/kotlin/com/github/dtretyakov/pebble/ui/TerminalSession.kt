package com.github.dtretyakov.pebble.ui

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/** The terminal plugin's side of [TerminalCommand]: only loaded where that plugin is. */
internal object TerminalSession {

    /**
     * Deprecated in 2026.2 for `TerminalToolWindowTabsManager`, which is experimental and lives in
     * the terminal's frontend module — a dependency this plugin cannot rely on back to 2026.1,
     * where `sinceBuild` promises it runs. Move when that API settles.
     */
    @Suppress("DEPRECATION")
    fun run(project: Project, command: String, directory: String?, title: String) {
        TerminalToolWindowManager.getInstance(project)
            .createShellWidget(directory, title, true, true)
            .sendCommandToExecute(command)
    }
}
