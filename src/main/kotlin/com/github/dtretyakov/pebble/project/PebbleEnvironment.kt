package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.PebbleTool
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import java.nio.file.Path

/**
 * Everything that has to be in place before a Pebble project can be built, as one checklist.
 *
 * Read from files only — no process is started to answer it — so it is cheap enough for the
 * editor banner to ask on every file that opens. Kept apart from the settings page so the rules
 * can be tested as rules, without a dialog.
 */
object PebbleEnvironment {

    enum class Status { READY, MISSING }

    /** What the plugin can do about a missing item. Null means there is nothing it can do. */
    enum class Fix { INSTALL_TOOL, INSTALL_SDK, ACTIVATE_SDK }

    /** Which prerequisite an item is about, so callers do not match on display text. */
    enum class Concern { HOST, TOOL, SDK, TOOLCHAIN, NODE }

    data class Item(
        val concern: Concern,
        val name: String,
        val status: Status,
        val detail: String,
        val fix: Fix? = null,
        /** Whether this stops a build, as opposed to being worth knowing. */
        val blocking: Boolean = true,
    )

    /** The machine as it is now. */
    fun check(): List<Item> {
        val service = PebbleSdkService.getInstance()
        return check(
            os = HostOs.current(),
            tool = service.tool(),
            configuredTool = PebbleAppSettings.getInstance().pebbleToolPath,
            sdks = service.sdks(),
            node = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("node")?.toPath(),
        )
    }

    /** The rules, as a function of what was found. */
    fun check(os: HostOs, tool: Path?, configuredTool: String, sdks: PebbleSdks, node: Path?): List<Item> {
        if (os == HostOs.WINDOWS) {
            // Everything below would be a list of consequences of this one fact.
            return listOf(
                Item(
                    Concern.HOST, "Windows", Status.MISSING,
                    "The Pebble SDK runs on Windows only inside WSL, which this version of the plugin does not " +
                        "drive yet. Build from a WSL terminal in the meantime.",
                ),
            )
        }

        return buildList {
            add(
                when {
                    tool != null -> Item(Concern.TOOL, "pebble-tool", Status.READY, tool.toString())
                    configuredTool.isNotBlank() -> Item(
                        Concern.TOOL, "pebble-tool", Status.MISSING,
                        "There is no pebble executable at $configuredTool.", Fix.INSTALL_TOOL,
                    )
                    else -> Item(
                        Concern.TOOL, "pebble-tool", Status.MISSING,
                        "pebble-tool is not installed, or not where it can be found. " +
                            "Install it with `uv tool install pebble-tool`.",
                        Fix.INSTALL_TOOL,
                    )
                },
            )

            val current = sdks.current
            add(
                when {
                    current != null -> Item(
                        Concern.SDK, "SDK", Status.READY,
                        listOfNotNull(current.version, current.channel?.takeIf { it != "release" }).joinToString(" "),
                    )
                    sdks.installed.isNotEmpty() -> Item(
                        Concern.SDK, "SDK", Status.MISSING,
                        "${sdks.installed.size} installed, none of them active.", Fix.ACTIVATE_SDK.takeIf { tool != null },
                    )
                    else -> Item(
                        Concern.SDK, "SDK", Status.MISSING,
                        "No Pebble SDK is installed.", Fix.INSTALL_SDK.takeIf { tool != null },
                    )
                },
            )

            if (current != null) {
                add(
                    if (current.hasCompiler) {
                        Item(Concern.TOOLCHAIN, "Toolchain", Status.READY, current.compilerBin.toString())
                    } else {
                        Item(
                            Concern.TOOLCHAIN, "Toolchain", Status.MISSING,
                            "SDK ${current.version} has no compiler. Reinstall it: " +
                                "`pebble sdk uninstall ${current.version}`, then `pebble sdk install ${current.version}`.",
                        )
                    },
                )
            }

            add(
                if (node != null) {
                    Item(Concern.NODE, "Node.js", Status.READY, node.toString(), blocking = false)
                } else {
                    Item(
                        Concern.NODE, "Node.js", Status.MISSING,
                        "Not found. Projects with PebbleKit JS or npm dependencies need it to build.",
                        blocking = false,
                    )
                },
            )
        }
    }

    /** The first thing actually in the way, or null when nothing is. */
    fun firstProblem(items: List<Item>): Item? = items.firstOrNull { it.status == Status.MISSING && it.blocking }

    const val INSTALL_URL = PebbleTool.INSTALL_URL
}
