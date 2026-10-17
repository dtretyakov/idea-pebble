package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.Clangd
import com.github.dtretyakov.pebble.sdk.EmulatorStart
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.HostPackages
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.util.EnvironmentUtil
import com.intellij.util.messages.Topic
import java.nio.file.Path

/**
 * Everything that has to be in place before a Pebble project can be built, as one checklist.
 *
 * Read from files only — no process is started to answer it — so it is cheap enough for the
 * editor banner to ask on every file that opens. The emulator's line is the one exception that
 * proves it: whether it starts takes a process to find out, so it is read from what [EmulatorCheck]
 * already found, and is absent until then. Kept apart from the settings page so the rules can be
 * tested as rules, without a dialog.
 */
object PebbleEnvironment {

    enum class Status { READY, MISSING }

    /** What the plugin can do about a missing item. Null means there is nothing it can do. */
    enum class Fix {
        /** Everything still missing of uv, pebble-tool and an SDK, in one go: [PebbleSdkSetup]. */
        SET_UP,
        ACTIVATE_SDK,

        /** A command that needs the user — `sudo`, or Homebrew — run where they can see it: [Item.command]. */
        RUN_IN_TERMINAL,
        INSTALL_LSP4IJ,

        /** Pages, for where there is no command to give. */
        HOW_TO_INSTALL_CLANGD,
        HOW_TO_INSTALL_NODE,
    }

    /**
     * What a missing item stops, which decides where it is said.
     *
     * Two levels used to be one too few. The emulator is not needed to build, so it was "worth
     * knowing" and said only on the settings page — but Run uses it by default, and for someone
     * new "it cannot run" is as much in the way as "it cannot build".
     */
    enum class Impact {
        /** Nothing builds: the balloon a project opens with, the banner over every file, Settings. */
        BUILD,

        /** Builds, but Run on the emulator cannot work: the balloon, the end of Set Up, Run, Settings. */
        RUN,

        /** Needed by some files only: the banner over those files, Settings. */
        SOME_FILES,
    }

    /** Which prerequisite an item is about, so callers do not match on display text. */
    enum class Concern { HOST, TOOL, SDK, TOOLCHAIN, EMULATOR, NODE, C_INSIGHT }

    data class Item(
        val concern: Concern,
        val name: String,
        val status: Status,
        val detail: String,
        val fix: Fix? = null,
        val impact: Impact = Impact.BUILD,
        /** For [Fix.RUN_IN_TERMINAL]: what to run. */
        val command: String? = null,
    )

    /** The machine as it is now. */
    fun check(): List<Item> {
        val service = PebbleSdkService.getInstance()
        val sdks = service.sdks()
        return check(
            os = HostOs.current(),
            tool = service.tool(),
            configuredTool = PebbleAppSettings.getInstance().pebbleToolPath,
            sdks = sdks,
            node = PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("node")?.toPath(),
            cInsight = when {
                IdeCapabilities.hasNativeC() -> CInsight.NATIVE
                !IdeCapabilities.hasLsp4ij() -> CInsight.NO_LSP4IJ
                else -> Clangd.locate(PebbleAppSettings.getInstance().clangdPath, EnvironmentUtil.getValue("PATH"))
                    ?.let { CInsight.Clangd(it) } ?: CInsight.NO_CLANGD
            },
            packages = HostPackages.current(EnvironmentUtil.getValue("PATH")),
            emulator = EmulatorCheck.getInstance().cached(sdks.current),
        )
    }

    /** How C gets its code insight in this IDE. */
    sealed interface CInsight {
        /** CLion: its own engine, reading compile_commands.json. */
        data object NATIVE : CInsight
        data object NO_LSP4IJ : CInsight
        data object NO_CLANGD : CInsight
        data class Clangd(val path: Path) : CInsight
    }

    /** The rules, as a function of what was found. */
    fun check(
        os: HostOs,
        tool: Path?,
        configuredTool: String,
        sdks: PebbleSdks,
        node: Path?,
        cInsight: CInsight = CInsight.NATIVE,
        packages: HostPackages = HostPackages.NONE,
        emulator: EmulatorStart.Verdict? = null,
    ): List<Item> {
        if (os == HostOs.WINDOWS) {
            // Everything below would be a list of consequences of this one fact.
            return listOf(
                Item(
                    Concern.HOST, "Windows", Status.MISSING,
                    "The Pebble SDK runs on Windows only inside WSL, which this version of the plugin does not " +
                        "drive yet. Install Ubuntu 24.04 in WSL (`wsl --install -d Ubuntu-24.04`; 22.04 is too old " +
                        "for the emulator), set up the SDK there and build from a WSL terminal — or use " +
                        "CloudPebble in the browser, which needs no installation.",
                ),
            )
        }

        return buildList {
            add(
                when {
                    tool != null -> Item(Concern.TOOL, "pebble-tool", Status.READY, "Found at $tool")
                    // Not offered Set Up: it would install where uv puts pebble-tool, and the setting
                    // would go on pointing somewhere else.
                    configuredTool.isNotBlank() -> Item(
                        Concern.TOOL, "pebble-tool", Status.MISSING,
                        "There is no pebble executable at $configuredTool. Correct the path, or clear it to use the one Set Up installs.",
                    )
                    else -> Item(
                        Concern.TOOL, "pebble-tool", Status.MISSING,
                        "pebble-tool is not installed, or not where it can be found.", Fix.SET_UP,
                    )
                },
            )

            val current = sdks.current
            add(
                when {
                    current != null -> Item(
                        Concern.SDK, "SDK", Status.READY,
                        listOfNotNull(current.version, current.channel?.takeIf { it.isNotBlank() && it != "release" })
                            .joinToString(" ") + " is active",
                    )
                    sdks.installed.isNotEmpty() -> Item(
                        Concern.SDK, "SDK", Status.MISSING,
                        "${sdks.installed.size} installed, none of them active.", Fix.ACTIVATE_SDK.takeIf { tool != null },
                    )
                    tool != null -> Item(
                        Concern.SDK, "SDK", Status.MISSING, "No Pebble SDK is installed.", Fix.SET_UP,
                    )
                    // Set Up installs both, and it is offered on pebble-tool's line; said here
                    // rather than offered twice.
                    else -> Item(
                        Concern.SDK, "SDK", Status.MISSING,
                        "No Pebble SDK is installed. Set Up installs it along with pebble-tool.",
                    )
                },
            )

            if (current != null) {
                add(
                    if (current.hasCompiler) {
                        Item(Concern.TOOLCHAIN, "Toolchain", Status.READY, "Compiler at ${current.compilerBin}")
                    } else {
                        Item(
                            Concern.TOOLCHAIN, "Toolchain", Status.MISSING,
                            "SDK ${current.version} has no compiler. Reinstall it: " +
                                "`pebble sdk uninstall ${current.version}`, then `pebble sdk install ${current.version}`.",
                        )
                    },
                )
            }

            if (current != null && current.hasCompiler) emulatorItem(emulator, packages)?.let(::add)

            add(
                if (node != null) {
                    Item(Concern.NODE, "Node.js", Status.READY, "Found at $node", impact = Impact.SOME_FILES)
                } else {
                    Item(
                        Concern.NODE, "Node.js", Status.MISSING,
                        "Not found. Projects with PebbleKit JS or npm dependencies need it to build.",
                        packages.node()?.let { Fix.RUN_IN_TERMINAL } ?: Fix.HOW_TO_INSTALL_NODE,
                        impact = Impact.SOME_FILES, command = packages.node(),
                    )
                },
            )

            // Worth knowing rather than in the way: builds and runs do not need it.
            when (cInsight) {
                CInsight.NATIVE -> Unit
                CInsight.NO_LSP4IJ -> add(
                    Item(
                        Concern.C_INSIGHT, "C code insight", Status.MISSING,
                        "This IDE has no C engine. Install the LSP4IJ plugin, and clangd, for completion and navigation in C.",
                        Fix.INSTALL_LSP4IJ, impact = Impact.SOME_FILES,
                    ),
                )
                CInsight.NO_CLANGD -> add(
                    Item(
                        Concern.C_INSIGHT, "C code insight", Status.MISSING,
                        "clangd is not installed; C has no completion or navigation in this IDE.",
                        packages.clangd()?.let { Fix.RUN_IN_TERMINAL } ?: Fix.HOW_TO_INSTALL_CLANGD,
                        impact = Impact.SOME_FILES, command = packages.clangd(),
                    ),
                )
                is CInsight.Clangd -> add(
                    Item(Concern.C_INSIGHT, "C code insight", Status.READY, "clangd at ${cInsight.path}", impact = Impact.SOME_FILES),
                )
            }
        }
    }

    /**
     * Whether the emulator starts; null until [EmulatorCheck] has asked it.
     *
     * [Impact.RUN]: builds and installs on a watch work without it, but it is what Run uses by
     * default.
     */
    private fun emulatorItem(verdict: EmulatorStart.Verdict?, packages: HostPackages): Item? = when (verdict) {
        null -> null
        EmulatorStart.Verdict.Starts -> Item(Concern.EMULATOR, "Emulator", Status.READY, "Starts", impact = Impact.RUN)
        is EmulatorStart.Verdict.MissingLibrary -> if (
            packages.os == HostOs.MAC && verdict.homebrewFormula != null && verdict.path != null && !packages.homebrewServes(verdict.path)
        ) {
            Item(
                Concern.EMULATOR, "Emulator", Status.MISSING,
                "The emulator cannot start: it looks for Homebrew's `${verdict.homebrewFormula}` in " +
                    "${verdict.path.substringBefore("/opt/${verdict.homebrewFormula}/")}, where Homebrew installs only on Apple silicon, so " +
                    "installing it here would not help. Building and installing on a watch work.",
                impact = Impact.RUN,
            )
        } else {
            val command = packages.emulatorLibraries(verdict.homebrewFormula)
            Item(
                Concern.EMULATOR, "Emulator", Status.MISSING,
                verdict.homebrewFormula?.let { "The emulator cannot start: it needs Homebrew's `$it`." }
                    ?: "The emulator cannot start: `${verdict.library}` is missing.",
                command?.let { Fix.RUN_IN_TERMINAL }, impact = Impact.RUN, command = command,
            )
        }
        is EmulatorStart.Verdict.OldGlibc -> Item(
            Concern.EMULATOR, "Emulator", Status.MISSING,
            "The emulator needs glibc ${verdict.needed} or later — Ubuntu 24.04 or later, for instance. " +
                "Building and installing on a watch work regardless.",
            impact = Impact.RUN,
        )
        is EmulatorStart.Verdict.Fails -> Item(
            Concern.EMULATOR, "Emulator", Status.MISSING, "The emulator does not start: ${verdict.message}", impact = Impact.RUN,
        )
    }

    /**
     * Told when something the checklist reads has changed outside a settings change or an SDK
     * command: the emulator has answered, or a command run in the terminal has taken effect.
     */
    fun interface Listener {
        fun changed()
    }

    val TOPIC: Topic<Listener> = Topic.create("Pebble environment", Listener::class.java)

    /** The first thing in the way of a build, or null when nothing is. */
    fun firstProblem(items: List<Item>): Item? =
        items.firstOrNull { it.status == Status.MISSING && it.impact == Impact.BUILD }

    /** The first thing in the way of building or of running on the emulator: build first. */
    fun firstRunProblem(items: List<Item>): Item? =
        firstProblem(items) ?: items.firstOrNull { it.status == Status.MISSING && it.impact == Impact.RUN }

    fun of(items: List<Item>, concern: Concern): Item? = items.firstOrNull { it.concern == concern }

    /**
     * An item's detail as a finished sentence, for text that has a link after it.
     *
     * A MISSING detail is already a sentence; a READY one is a fragment naming a path or a version,
     * which reads worse with a full stop of its own and needs one before a link.
     */
    fun sentence(item: Item): String = item.detail.let { if (it.isEmpty() || it.last() in ".!?") it else "$it." }

    /**
     * A detail as HTML for a label: escaped, and with `code` in backticks set as code.
     *
     * Details are written with backticks so they stay readable in a log and in the self-check;
     * Swing shows a backtick as a backtick, which is how `uv tool install` came to be on screen
     * wrapped in them.
     */
    fun html(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace(Regex("`([^`]+)`"), "<code>$1</code>")
}
