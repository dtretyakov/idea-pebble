package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Fix
import com.github.dtretyakov.pebble.project.PebbleSdkSetup
import com.github.dtretyakov.pebble.project.ToolHosts
import com.github.dtretyakov.pebble.sdk.Clangd
import com.github.dtretyakov.pebble.sdk.HostPackages
import com.github.dtretyakov.pebble.sdk.ToolHostState
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project

/**
 * What each fix is called and what it does, wherever it is offered: the settings page, the editor
 * banner and the balloon a project opens with. Kept in one place so they cannot drift apart again.
 */
internal object EnvironmentFixes {

    /**
     * What the button says. A short command without `sudo` is the label itself — "Run brew install
     * libpng" says exactly what will happen; a `sudo` one is shown in the text instead, to be read
     * before the password is typed, and the button is "Run in Terminal".
     */
    fun label(item: PebbleEnvironment.Item): String {
        val fix = item.fix ?: return ""
        if (fix == Fix.USE_PROJECT_DISTRIBUTION) return projectDistribution()?.let { "Use $it" } ?: label(fix)
        val command = item.command?.takeIf { fix == Fix.RUN_IN_TERMINAL } ?: return label(fix)
        if (command == PebbleEnvironment.INSTALL_WSL) return "Install Ubuntu in WSL"
        homebrewFirst(command)?.let { return "Install Homebrew and $it" }
        return if (inLabel(command)) "Run ${shown(command)}" else label(fix)
    }

    fun label(fix: Fix): String = when (fix) {
        Fix.SET_UP -> PebbleSdkSetup.LABEL
        Fix.ACTIVATE_SDK -> "Activate the newest SDK"
        Fix.RUN_IN_TERMINAL -> "Run in Terminal"
        Fix.INSTALL_LSP4IJ -> "Open Plugins"
        Fix.HOW_TO_INSTALL_CLANGD -> "How to install clangd"
        Fix.HOW_TO_INSTALL_NODE -> "How to install Node.js"
        Fix.USE_PROJECT_DISTRIBUTION -> "Use the project's distribution"
    }

    fun run(project: Project?, item: PebbleEnvironment.Item) {
        when (item.fix ?: return) {
            Fix.SET_UP -> PebbleSdkSetup.start(project)
            Fix.ACTIVATE_SDK -> PebbleCommands.activateNewestSdk(project)
            Fix.RUN_IN_TERMINAL -> item.command?.let {
                TerminalCommand.run(project, it, inToolHost = item.terminal == PebbleEnvironment.Terminal.TOOL_HOST)
                FixWatcher.watch(item.concern)
            }
            Fix.INSTALL_LSP4IJ -> ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins")
            Fix.HOW_TO_INSTALL_CLANGD -> BrowserUtil.browse(Clangd.INSTALL_URL)
            Fix.HOW_TO_INSTALL_NODE -> BrowserUtil.browse(NODE_URL)
            Fix.USE_PROJECT_DISTRIBUTION -> projectDistribution()?.let {
                PebbleAppSettings.getInstance().wslDistribution = it
                ToolHosts.reset()
            }
        }
    }

    private fun projectDistribution(): String? = (ToolHosts.state() as? ToolHostState.Wsl)?.projectDistribution

    /**
     * The item's detail as HTML, and the command a terminal fix would run: a command the user is
     * about to run, often with `sudo`, is shown before the button that runs it.
     */
    fun text(item: PebbleEnvironment.Item): String {
        val sentence = PebbleEnvironment.html(PebbleEnvironment.sentence(item))
        val command = item.command?.takeIf { item.fix == Fix.RUN_IN_TERMINAL } ?: return sentence
        if (homebrewFirst(command) != null || inLabel(command)) return sentence
        return "$sentence Install with <code>${PebbleEnvironment.html(shown(command))}</code>."
    }

    /** Homebrew by name, rather than by the path it is run by. */
    fun shown(command: String): String = command.replace(Regex("""\S*/brew(?=\s)"""), "brew")

    private fun inLabel(command: String): Boolean = "sudo" !in command && shown(command).length <= MAX_LABEL

    /** The formula, when the command installs Homebrew first: too long to show, and Homebrew's installer explains itself. */
    private fun homebrewFirst(command: String): String? =
        command.takeIf { HostPackages.HOMEBREW_INSTALLER in it }?.substringAfterLast("brew install ")?.trim()

    /** A link for HTML text, followed by [follow]. The href names the item's concern. */
    fun link(item: PebbleEnvironment.Item): String? =
        item.fix?.let { "<a href='$SCHEME${item.concern.name}'>${label(item)}</a>" }

    /** Runs the fix a [link] names — for the machine as it is now — or opens a web address. */
    fun follow(project: Project?, href: String?) {
        href ?: return
        if (!href.startsWith(SCHEME)) return BrowserUtil.browse(href)
        val concern = PebbleEnvironment.Concern.entries.firstOrNull { it.name == href.removePrefix(SCHEME) } ?: return
        PebbleEnvironment.of(PebbleEnvironment.check(), concern)?.let { run(project, it) }
    }

    private const val SCHEME = "fix:"
    private const val MAX_LABEL = 40
    private const val NODE_URL = "https://nodejs.org/en/download"
}
