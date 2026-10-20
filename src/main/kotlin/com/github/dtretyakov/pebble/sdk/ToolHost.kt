package com.github.dtretyakov.pebble.sdk

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The machine the tools run on — pebble-tool, waf and gcc, the emulator, gdb — which is not always
 * the one the IDE runs on.
 *
 * On a Mac or Linux it is the same machine ([LocalToolHost]). On Windows the SDK runs only inside
 * WSL, so it is a Linux distribution ([WslToolHost]): the IDE reads the SDK's files through
 * `\\wsl.localhost\<distribution>\…`, and starts every tool through `wsl.exe`. [HostOs] stays the
 * answer to "where does the IDE run"; this is the answer to "where do the tools run", and the two
 * questions were one until Windows.
 *
 * Paths come in two spellings. The IDE's — a [Path] it can open, `C:\Users\me\app` or
 * `\\wsl.localhost\Ubuntu\home\me\app` — and the tools' — `/mnt/c/Users/me/app`, `/home/me/app` —
 * which is what a path handed to a tool must be, and what a tool's output names. [toolPath] and
 * [idePath] go between them; locally they change nothing.
 */
interface ToolHost {

    /** Stable for the same machine, so caches can be keyed by it: "" locally, "wsl:Ubuntu" for a distribution. */
    val id: String

    /** For the user: "this computer", or the distribution's name. */
    val presentableName: String

    /** The system the tools run on: LINUX for every WSL distribution. */
    val os: HostOs

    /** `uname -m` of the tools' machine — "x86_64", "aarch64" — or empty while unknown. */
    val arch: String

    /** The tools' home directory, as the IDE reads it. */
    val home: Path?

    /** The tools' temporary directory, where pebble-tool keeps `pb-emulator.json`. */
    val tempDirectory: Path?

    /** A variable of the tools' environment. */
    fun env(name: String): String?

    /** The tools' spelling of [path]; null when the tools cannot reach it. */
    fun toolPath(path: Path): String?

    /** The IDE's spelling of a path a tool printed; null when the IDE cannot reach it. */
    fun idePath(path: String): Path?

    /** Whether a process the tools started — a pid pebble-tool recorded — is still running. */
    fun isProcessAlive(pid: Long): Boolean

    /** Where [tool] is, the way the plugin has always looked for it: [configured] first, when given. */
    fun locate(tool: HostTool, configured: String = ""): Path?

    /** `/etc/os-release` of the tools' system, when it has one. */
    fun osRelease(): String?

    /**
     * Whether [sdk]'s JavaScript tools — webpack, which bundles PebbleKit JS — can run: null when
     * the SDK has none, or it cannot be told. False after an install whose `npm install` was not
     * this system's own (see [PebbleSdk.WEBPACK]).
     */
    fun sdkJavaScriptReady(sdk: PebbleSdk): Boolean?

    /** Where the symbolic link [link] leads, as the IDE spells it; null when it leads nowhere. */
    fun resolveLink(link: Path): Path?

    /** [executable] with [arguments], run in [workDirectory] with [environment] added to the tools' own. */
    fun command(executable: Path, arguments: List<String>, workDirectory: Path?, environment: Map<String, String> = emptyMap()): HostCommand

    /** A POSIX [script] for the tools' `/bin/sh`, run in [workDirectory] with [environment] added. */
    fun shell(script: String, workDirectory: Path?, environment: Map<String, String> = emptyMap()): HostCommand
}

/** The tools the plugin finds rather than runs from the SDK. */
enum class HostTool { PEBBLE, UV, CLANGD, NODE, NPM }

/**
 * A command as the IDE's process will be started: these [words], in [workDirectory] (when the
 * process itself is to have one), with [environment] added to the IDE's.
 */
data class HostCommand(val words: List<String>, val workDirectory: Path?, val environment: Map<String, String>)

/** What the environment checklist needs to know about the tool host, without asking it anything. */
sealed interface ToolHostState {
    /** This machine: a Mac or Linux. */
    data object Local : ToolHostState

    /** Windows, while WSL is asked which distributions it has. */
    data object Listing : ToolHostState

    /** Windows with no WSL distribution to run the SDK in. */
    data object NoWsl : ToolHostState

    /**
     * A WSL distribution. [ready] once it has said where its tools are; [problem] when it could not;
     * [projectDistribution] when an open project lives in another distribution, whose files this
     * one's tools cannot see.
     */
    data class Wsl(
        val name: String,
        val ready: Boolean,
        val problem: String? = null,
        val projectDistribution: String? = null,
    ) : ToolHostState
}

/**
 * This machine. [environment] is the IDE's view of the user's shell environment — the platform's
 * `EnvironmentUtil` in the IDE, `System.getenv` in tests — since an IDE started from the Dock has
 * a PATH of its own.
 */
class LocalToolHost(
    override val os: HostOs = HostOs.current(),
    override val home: Path = Path.of(System.getProperty("user.home")),
    private val environment: (String) -> String? = System::getenv,
    private val osReleaseFile: Path = Path.of("/etc/os-release"),
) : ToolHost {

    override val id: String = ""
    override val presentableName: String = "this computer"
    override val arch: String = System.getProperty("os.arch").orEmpty()
    override val tempDirectory: Path = Path.of(System.getProperty("java.io.tmpdir"))

    override fun env(name: String): String? = environment(name)

    override fun toolPath(path: Path): String = path.toString()

    override fun idePath(path: String): Path? = runCatching { Path.of(path) }.getOrNull()

    override fun isProcessAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)

    override fun locate(tool: HostTool, configured: String): Path? {
        val path = env("PATH")
        return when (tool) {
            HostTool.PEBBLE -> PebbleTool.locate(configured, path, home, ::env)
            HostTool.UV -> Uv.locate(path, home, ::env)
            HostTool.CLANGD -> Clangd.locate(configured, path)
            HostTool.NODE -> onPath(path, if (os == HostOs.WINDOWS) "node.exe" else "node")
            HostTool.NPM -> onPath(path, if (os == HostOs.WINDOWS) "npm.cmd" else "npm")
        }
    }

    private fun onPath(path: String?, name: String): Path? =
        path.orEmpty().split(File.pathSeparatorChar).filter { it.isNotBlank() }
            .map { Path.of(it).resolve(name) }
            .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }

    override fun sdkJavaScriptReady(sdk: PebbleSdk): Boolean? {
        if (!Files.isRegularFile(sdk.root.resolve("package.json"))) return null
        return Files.isExecutable(sdk.root.resolve(PebbleSdk.WEBPACK))
    }

    private val release: String? by lazy { runCatching { Files.readString(osReleaseFile) }.getOrNull() }

    override fun osRelease(): String? = if (os == HostOs.LINUX) release else null

    override fun resolveLink(link: Path): Path? = runCatching { link.toRealPath() }.getOrNull()

    override fun command(executable: Path, arguments: List<String>, workDirectory: Path?, environment: Map<String, String>): HostCommand =
        HostCommand(listOf(executable.toString()) + arguments, workDirectory, environment)

    override fun shell(script: String, workDirectory: Path?, environment: Map<String, String>): HostCommand =
        HostCommand(listOf("/bin/sh", "-c", script), workDirectory, environment)
}
