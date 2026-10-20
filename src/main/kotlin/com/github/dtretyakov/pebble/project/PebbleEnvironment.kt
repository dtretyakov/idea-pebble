package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.EmulatorStart
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.HostPackages
import com.github.dtretyakov.pebble.sdk.HostTool
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.ToolHostState
import com.github.dtretyakov.pebble.sdk.WslToolHost
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

        /** Windows: run the tools in the WSL distribution the open project is in, rather than the one Settings names. */
        USE_PROJECT_DISTRIBUTION,
    }

    /** Where a [Fix.RUN_IN_TERMINAL] command runs: where the tools are, or — to install WSL itself — Windows. */
    enum class Terminal { TOOL_HOST, WINDOWS }

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
        /** For [Fix.RUN_IN_TERMINAL]: where. */
        val terminal: Terminal = Terminal.TOOL_HOST,
        /**
         * Not a problem but a moment: WSL is being asked where its tools are. In the way of a build
         * like a problem, and shown nowhere it would flash by — the setup balloon, the banner.
         */
        val pending: Boolean = false,
    )

    /** The machine as it is now. */
    fun check(): List<Item> {
        val service = PebbleSdkService.getInstance()
        val settings = PebbleAppSettings.getInstance()
        val state = ToolHosts.state()
        val host = service.host()
        val sdks = service.sdks()
        return check(
            host = state,
            tool = service.tool(),
            configuredTool = settings.pebbleToolPath,
            sdks = sdks,
            node = host?.locate(HostTool.NODE),
            cInsight = when {
                IdeCapabilities.hasNativeC() -> CInsight.NATIVE
                !IdeCapabilities.hasLsp4ij() -> CInsight.NO_LSP4IJ
                else -> host?.locate(HostTool.CLANGD, settings.clangdPath)?.let { CInsight.Clangd(it) } ?: CInsight.NO_CLANGD
            },
            packages = HostPackages.current(host),
            emulator = EmulatorCheck.getInstance().cached(sdks.current),
            toolSpelling = { path -> host?.toolPath(path) ?: path.toString() },
            npm = host?.locate(HostTool.NPM),
            sdkJavaScript = sdks.current?.let { sdk -> host?.sdkJavaScriptReady(sdk) },
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
        host: ToolHostState,
        tool: Path?,
        configuredTool: String,
        sdks: PebbleSdks,
        node: Path?,
        cInsight: CInsight = CInsight.NATIVE,
        packages: HostPackages = HostPackages.NONE,
        emulator: EmulatorStart.Verdict? = null,
        /** How a path is shown: as the user would type it where the tools are — `/home/me/…` under WSL. */
        toolSpelling: (Path) -> String = Path::toString,
        /** npm, which the SDK's installer runs; beside Node.js wherever a package manager installs both. */
        npm: Path? = node,
        /** Whether the active SDK's JavaScript tools run ([com.github.dtretyakov.pebble.sdk.ToolHost.sdkJavaScriptReady]). */
        sdkJavaScript: Boolean? = null,
    ): List<Item> {
        // Each of these is the one thing to say: everything below would be its consequences.
        hostProblem(host)?.let { return listOf(it) }

        return buildList {
            if (host is ToolHostState.Wsl) {
                add(Item(Concern.HOST, "WSL", Status.READY, "Tools run in the WSL distribution ${host.name}"))
            }
            add(
                when {
                    tool != null -> Item(Concern.TOOL, "pebble-tool", Status.READY, "Found at ${toolSpelling(tool)}")
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
                        Item(Concern.TOOLCHAIN, "Toolchain", Status.READY, "Compiler at ${toolSpelling(current.compilerBin)}")
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

            val javaScript = node != null && npm != null
            when {
                // `pebble sdk install` runs npm, and fails without it: before the SDK, Node.js is
                // what Set Up needs first. One command for everything the system has to provide,
                // so that the password is asked once and Set Up then needs nothing more.
                !javaScript && sdks.installed.isEmpty() -> {
                    // clangd too, where this IDE would ask for it next: one password rather than two.
                    val command = packages.sdkPrerequisites(withClangd = cInsight == CInsight.NO_CLANGD)
                    val also = if (command != null && command != packages.node()) ", and the emulator's libraries with them" else ""
                    // Before pebble-tool's line, so it is the first thing in the way.
                    add(
                        indexOfFirst { it.concern == Concern.TOOL }.coerceAtLeast(0),
                        Item(
                            Concern.NODE, "Node.js", Status.MISSING,
                            if (node == null) "The SDK's installer runs npm, so Node.js and npm come before it$also."
                            else "npm is not installed beside Node.js, and the SDK's installer runs it, so it comes before the SDK$also.",
                            command?.let { Fix.RUN_IN_TERMINAL } ?: Fix.HOW_TO_INSTALL_NODE,
                            command = command,
                        ),
                    )
                }
                !javaScript -> {
                    val command = packages.node()?.let { install -> repairJavaScript(current, sdkJavaScript, toolSpelling)?.let { "$install && $it" } ?: install }
                    add(
                        Item(
                            Concern.NODE, "Node.js", Status.MISSING,
                            if (node == null) "Not found. Projects with PebbleKit JS or npm dependencies need it to build."
                            else "npm is not installed beside Node.js. Projects with PebbleKit JS or npm dependencies need it to build.",
                            command?.let { Fix.RUN_IN_TERMINAL } ?: Fix.HOW_TO_INSTALL_NODE,
                            impact = Impact.SOME_FILES, command = command,
                        ),
                    )
                }
                current != null && sdkJavaScript == false -> add(
                    Item(
                        Concern.NODE, "Node.js", Status.MISSING,
                        "The SDK's JavaScript tools were installed by another npm — Windows', under WSL — and cannot run here, " +
                            "so projects with PebbleKit JS do not build. `npm rebuild` in the SDK makes them this system's.",
                        Fix.RUN_IN_TERMINAL, impact = Impact.SOME_FILES, command = repairJavaScript(current, false, toolSpelling),
                    ),
                )
                else -> add(Item(Concern.NODE, "Node.js", Status.READY, "Found at ${toolSpelling(node!!)}", impact = Impact.SOME_FILES))
            }

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
                    Item(Concern.C_INSIGHT, "C code insight", Status.READY, "clangd at ${toolSpelling(cInsight.path)}", impact = Impact.SOME_FILES),
                )
            }
        }
    }

    /** `npm rebuild` in [sdk], when its JavaScript tools are known not to run. */
    private fun repairJavaScript(sdk: com.github.dtretyakov.pebble.sdk.PebbleSdk?, ready: Boolean?, spell: (Path) -> String): String? {
        if (sdk == null || ready != false) return null
        return "cd ${WslToolHost.shellQuote(spell(sdk.root))} && npm rebuild --silent"
    }

    /** The command that installs what the SDK runs in on Windows: Ubuntu, in a release new enough for the emulator. */
    const val INSTALL_WSL = "wsl --install -d Ubuntu-24.04"

    private fun hostProblem(host: ToolHostState): Item? = when (host) {
        ToolHostState.Local -> null
        ToolHostState.Listing -> Item(Concern.HOST, "WSL", Status.MISSING, "Looking for WSL distributions…", pending = true)
        ToolHostState.NoWsl -> Item(
            Concern.HOST, "WSL", Status.MISSING,
            "The Pebble SDK runs on Windows inside WSL, and there is no WSL distribution here yet. " +
                "`$INSTALL_WSL` installs Ubuntu 24.04 — 22.04 is too old for the emulator; Windows may ask to restart " +
                "and Ubuntu for a user name and password. The SDK itself is set up from here afterwards.",
            Fix.RUN_IN_TERMINAL, command = INSTALL_WSL, terminal = Terminal.WINDOWS,
        )
        is ToolHostState.Wsl -> when {
            !host.ready && host.problem == null -> Item(
                Concern.HOST, "WSL", Status.MISSING,
                "Asking ${host.name} where its tools are — a few seconds the first time, while WSL starts.",
                pending = true,
            )
            host.problem != null -> Item(
                Concern.HOST, "WSL", Status.MISSING,
                "The WSL distribution ${host.name}, where the SDK runs, does not answer: ${host.problem.trimEnd('.')}. " +
                    "Open it once from the Start menu to finish its setup, or choose another in Settings.",
            )
            host.projectDistribution != null -> Item(
                Concern.HOST, "WSL", Status.MISSING,
                "This project is in the WSL distribution ${host.projectDistribution}, and pebble-tool runs in ${host.name}, " +
                    "which cannot see its files.",
                Fix.USE_PROJECT_DISTRIBUTION,
            )
            else -> null
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
