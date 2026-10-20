package com.github.dtretyakov.pebble.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A WSL distribution, as the machine the tools run on.
 *
 * Its files are read through Windows' share for it, `\\wsl.localhost\<distribution>\…` — an SDK
 * is a tree of ordinary files and directories, and reads well that way. What the share does not
 * show are symbolic links: `~/.local/bin/pebble`, which uv installs as one, the SDK's `current`,
 * `/etc/os-release`. Whatever those are needed for is asked of the distribution itself, in one
 * process, by [refresh]: where each tool is, where the data directory and the active SDK are, the
 * home directory, the processor. The answer is kept until the next [refresh], which the IDE asks
 * for in the background — the environment checklist, which reads it, runs on the UI thread.
 *
 * Every tool is started as `wsl.exe --distribution <d> --cd <dir> --exec /bin/sh -lc …`: a login
 * shell, so the PATH is the one a WSL terminal has, and `exec`, so the process the IDE holds ends
 * when the tool does and stopping it stops the tool.
 */
class WslToolHost(
    val distribution: String,
    /** How the IDE spells the distribution's share: `\\wsl.localhost\` or `\\wsl$\`, as the project was opened. */
    val uncPrefix: String = WslPaths.DEFAULT_PREFIX,
    val wslExe: String = WslDistributions.EXE,
    private val runner: Runner = Runner.PROCESS,
) : ToolHost {

    /** Runs a command to its end; null when it could not run or took longer than [timeoutMillis]. */
    fun interface Runner {
        fun run(words: List<String>, timeoutMillis: Long): Output?

        companion object {
            val PROCESS = Runner { words, timeout -> WslProcesses.run(words, timeout) }
        }
    }

    data class Output(val exitCode: Int, val stdout: String, val stderr: String)

    /** What the distribution said about itself, the last time it was asked. */
    @Volatile
    var probe: WslProbe? = null
        private set

    /** Why the distribution could not be asked, when it could not. */
    @Volatile
    var problem: String? = null
        private set

    override val id: String get() = "wsl:$distribution"
    override val presentableName: String get() = distribution
    override val os: HostOs get() = HostOs.LINUX
    override val arch: String get() = probe?.arch.orEmpty()

    private val mountRoot: String get() = probe?.mountRoot ?: WslPaths.DEFAULT_MOUNT_ROOT

    override val home: Path? get() = probe?.home?.let(::idePath)
    override val tempDirectory: Path? get() = idePath(probe?.temp ?: "/tmp")

    override fun env(name: String): String? = probe?.env?.get(name)?.takeIf { it.isNotEmpty() }

    override fun toolPath(path: Path): String? = WslPaths.toLinux(path.toString(), distribution, mountRoot)

    override fun idePath(path: String): Path? =
        WslPaths.toWindows(path, distribution, uncPrefix, mountRoot)?.let { runCatching { Path.of(it) }.getOrNull() }

    /** `/proc/<pid>` is a directory while the process lives, and the share shows it. */
    override fun isProcessAlive(pid: Long): Boolean = idePath("/proc/$pid")?.let(Files::isDirectory) == true

    /** The configured paths the last [refresh] asked about, in the distribution's spelling. */
    @Volatile
    private var askedAbout: Map<HostTool, String> = emptyMap()

    /**
     * As found by the last [refresh] — when it was asked about [configured]: a setting changed
     * since then is not answered for until the distribution has been asked again.
     */
    override fun locate(tool: HostTool, configured: String): Path? {
        if (tool == HostTool.PEBBLE || tool == HostTool.CLANGD) {
            if (linux(configured.trim()) != askedAbout[tool].orEmpty()) return null
        }
        return probe?.tools?.get(tool)?.let(::idePath)
    }

    override fun osRelease(): String? = probe?.osRelease

    /** Asked of the distribution, which sees the bit the share does not: for the active SDK only. */
    override fun sdkJavaScriptReady(sdk: PebbleSdk): Boolean? {
        val probe = probe ?: return null
        if (toolPath(sdk.root) != probe.currentSdk) return null
        return probe.sdkJavaScript
    }

    override fun resolveLink(link: Path): Path? {
        val probe = probe ?: return null
        val spelled = toolPath(link) ?: return null
        if (spelled == "${probe.dataDirectory}/SDKs/current") return probe.currentSdk?.let(::idePath)
        // Not a link the probe followed: through the share a directory is itself, a link is nothing.
        return runCatching { link.toRealPath() }.getOrNull()
    }

    override fun command(executable: Path, arguments: List<String>, workDirectory: Path?, environment: Map<String, String>): HostCommand {
        // A Linux path made a Path on Windows reads `\usr\bin\python3`: still the distribution's.
        val program = toolPath(executable) ?: executable.toString().replace('\\', '/').takeIf { it.startsWith("/") && !it.startsWith("//") }
            ?: throw IllegalArgumentException("$executable is not inside $distribution.")
        return HostCommand(prefix(workDirectory) + listOf(exports(environment) + "exec \"\$0\" \"\$@\"", program) + arguments, null, WSL_ENVIRONMENT)
    }

    override fun shell(script: String, workDirectory: Path?, environment: Map<String, String>): HostCommand =
        HostCommand(prefix(workDirectory) + listOf(exports(environment) + script), null, WSL_ENVIRONMENT)

    /** `wsl.exe` as far as the shell's script: `--cd` takes a Linux path, and `~` for the home directory. */
    private fun prefix(workDirectory: Path?): List<String> {
        // As given first: a relative one — "." — made absolute, but a Windows path stays one off Windows too.
        val directory = workDirectory?.let { toolPath(it) ?: toolPath(it.toAbsolutePath()) ?: throw IllegalArgumentException(unreachable(it)) } ?: "~"
        return listOf(wslExe, "--distribution", distribution, "--cd", directory, "--exec", "/bin/sh", "-lc")
    }

    fun unreachable(path: Path): String =
        "$path is not reachable from the WSL distribution $distribution, where pebble-tool runs. " +
            "Open the project from $distribution or from a Windows drive, or choose its distribution in Settings | Languages & Frameworks | Pebble."

    /**
     * The environment, and a PATH without Windows' directories. WSL appends Windows' PATH to its
     * own, and a Windows Node.js there is found by `npm`'s name: pebble-tool, installing an SDK,
     * then runs Windows' npm, which writes `webpack.cmd` where the build needs a Linux `webpack`, and
     * the first build of an app with PebbleKit JS fails on it. Nothing pebble-tool runs is Windows'.
     */
    private fun exports(environment: Map<String, String>): String {
        val mount = mountRoot.trimEnd('/')
        val linuxOnly = "PATH=\$(printf %s \"\$PATH\" | tr : '\\n' | grep -v '^$mount/[a-z]/' | paste -sd: -); export PATH; "
        return linuxOnly + environment.entries.joinToString("") { (name, value) -> "export $name=${shellQuote(value)}; " }
    }

    /**
     * Asks the distribution where everything is, and keeps the answer. True when the answer
     * differs from the one before — something was installed, or removed, meanwhile.
     */
    @Synchronized
    fun refresh(configuredPebble: String = "", configuredClangd: String = "", timeoutMillis: Long = PROBE_MILLIS): Boolean {
        val words = listOf(wslExe, "--distribution", distribution, "--cd", "~", "--exec", "/bin/sh", "-lc", PROBE_SCRIPT, "sh", linux(configuredPebble), linux(configuredClangd))
        val output = runner.run(words, timeoutMillis)
        val parsed = output?.takeIf { it.exitCode == 0 }?.let { WslProbe.parse(it.stdout) }
        val before = Triple(probe, problem, askedAbout)
        if (parsed != null) {
            probe = parsed
            problem = null
            askedAbout = mapOf(HostTool.PEBBLE to linux(configuredPebble.trim()), HostTool.CLANGD to linux(configuredClangd.trim()))
        } else {
            problem = when {
                output == null -> "WSL did not answer within ${timeoutMillis / 1000} seconds."
                else -> WslProcesses.lastWords(output).ifEmpty { "wsl.exe ended with exit code ${output.exitCode}." }
            }
        }
        return Triple(probe, problem, askedAbout) != before
    }

    /** Whether [path] is an executable file in the distribution: for a path the user typed, or chose in Windows' file dialog. */
    fun isExecutable(path: String): Boolean =
        runner.run(listOf(wslExe, "--distribution", distribution, "--exec", "/bin/sh", "-c", "[ -f \"\$1\" ] && [ -x \"\$1\" ]", "sh", linux(path)), CHECK_MILLIS)
            ?.exitCode == 0

    /** A path from a setting as the distribution spells it: typed as a Linux path, or chosen as a Windows one. */
    fun linux(path: String): String =
        if (path.isBlank() || path.startsWith("/")) path else runCatching { toolPath(Path.of(path)) }.getOrNull() ?: path

    override fun toString(): String = "WSL $distribution"
    override fun equals(other: Any?): Boolean = other is WslToolHost && other.distribution == distribution && other.uncPrefix == uncPrefix
    override fun hashCode(): Int = distribution.hashCode() * 31 + uncPrefix.hashCode()

    companion object {
        /** Asked for every command run through wsl.exe: its own messages in UTF-8 rather than UTF-16. */
        val WSL_ENVIRONMENT = mapOf("WSL_UTF8" to "1")

        /** A cold start of the WSL machine takes some seconds; this is room for a slow one. */
        const val PROBE_MILLIS = 60_000L
        private const val CHECK_MILLIS = 20_000L

        fun shellQuote(text: String): String =
            if (text.isNotEmpty() && text.all { it.isLetterOrDigit() || it in "_./:@%+=-," }) text else "'" + text.replace("'", "'\\''") + "'"

        /**
         * What [WslProbe.parse] reads, one `key=value` a line. The tools are looked for as
         * [PebbleTool], [Uv] and [Clangd] look on a Mac or Linux, with one difference: WSL puts
         * Windows' PATH after Linux's, and a `node` or `clangd` found under the Windows drives is
         * Windows' own, which no Linux tool can use.
         */
        val PROBE_SCRIPT: String = """
            c=$(wslpath -u 'C:\' 2>/dev/null); m=${'$'}{c%c/}; [ -n "${'$'}m" ] || m=/mnt/
            d="${'$'}HOME/.pebble-sdk"; [ -d "${'$'}d" ] || d="${'$'}{XDG_DATA_HOME:-${'$'}HOME/.local/share}/pebble-sdk"
            ok() { [ -n "${'$'}1" ] && [ -f "${'$'}1" ] && [ -x "${'$'}1" ]; }
            first() { for p in "${'$'}@"; do if ok "${'$'}p"; then echo "${'$'}p"; return 0; fi; done; return 1; }
            onpath() { o=${'$'}IFS; IFS=:; for dir in ${'$'}PATH; do case "${'$'}dir" in ""|"${'$'}m"*) continue;; esac; if ok "${'$'}dir/${'$'}1"; then IFS=${'$'}o; echo "${'$'}dir/${'$'}1"; return 0; fi; done; IFS=${'$'}o; return 1; }
            echo "home=${'$'}HOME"
            echo "data=${'$'}d"
            echo "temp=${'$'}{TMPDIR:-/tmp}"
            echo "arch=$(uname -m)"
            echo "mount=${'$'}m"
            [ -e "${'$'}d/SDKs/current" ] && echo "current=$(readlink -f "${'$'}d/SDKs/current")"
            s=$(readlink -f "${'$'}d/SDKs/current" 2>/dev/null)
            if [ -n "${'$'}s" ] && [ -f "${'$'}s/package.json" ]; then if [ -x "${'$'}s/${PebbleSdk.WEBPACK}" ]; then echo "sdkjs=ok"; else echo "sdkjs=broken"; fi; fi
            echo "npm=$(onpath npm)"
            if [ -n "${'$'}1" ]; then echo "pebble=$(first "${'$'}1")"; else echo "pebble=$(onpath pebble || first "${'$'}UV_TOOL_BIN_DIR/pebble" "${'$'}XDG_BIN_HOME/pebble" "${'$'}HOME/.local/bin/pebble")"; fi
            echo "uv=$(onpath uv || first "${'$'}XDG_BIN_HOME/uv" "${'$'}HOME/.local/bin/uv" "${'$'}HOME/.cargo/bin/uv")"
            echo "node=$(onpath node)"
            if [ -n "${'$'}2" ]; then echo "clangd=$(first "${'$'}2")"; else echo "clangd=$(onpath clangd || for v in 24 23 22 21 20 19 18 17 16 15 14; do onpath clangd-${'$'}v && break; done)"; fi
            echo "networking=$(wslinfo --networking-mode 2>/dev/null)"
            for v in PATH XDG_DATA_HOME XDG_BIN_HOME UV_TOOL_BIN_DIR; do eval "echo \"env.${'$'}v=\${'$'}${'$'}v\""; done
            [ -r /etc/os-release ] && sed 's/^/os./' /etc/os-release
            exit 0
        """.trimIndent()
    }
}

/** What a WSL distribution said about itself: see [WslToolHost.PROBE_SCRIPT]. All paths are Linux paths. */
data class WslProbe(
    val home: String,
    val dataDirectory: String,
    val temp: String,
    val arch: String,
    val mountRoot: String,
    val currentSdk: String?,
    val tools: Map<HostTool, String>,
    val networking: String?,
    val env: Map<String, String>,
    val osRelease: String,
    /** Whether the active SDK's webpack runs; null when the SDK has no JavaScript tools, or none is active. */
    val sdkJavaScript: Boolean? = null,
) {
    companion object {
        fun parse(output: String): WslProbe? {
            val values = mutableMapOf<String, String>()
            val env = mutableMapOf<String, String>()
            val release = StringBuilder()
            output.lineSequence().map { it.trimEnd('\r') }.forEach { line ->
                val key = line.substringBefore('=', "")
                val value = line.substringAfter('=', "")
                when {
                    key.isEmpty() -> Unit
                    key.startsWith("env.") -> env[key.removePrefix("env.")] = value
                    key.startsWith("os.") -> release.append(line.removePrefix("os.")).append('\n')
                    else -> values[key] = value
                }
            }
            val home = values["home"]?.takeIf { it.startsWith("/") } ?: return null
            val tools = mapOf(HostTool.PEBBLE to "pebble", HostTool.UV to "uv", HostTool.NODE to "node", HostTool.NPM to "npm", HostTool.CLANGD to "clangd")
                .mapNotNull { (tool, key) -> values[key]?.takeIf { it.startsWith("/") }?.let { tool to it } }
                .toMap()
            return WslProbe(
                home = home,
                dataDirectory = values["data"]?.takeIf { it.startsWith("/") } ?: "$home/.local/share/pebble-sdk",
                temp = values["temp"]?.takeIf { it.startsWith("/") } ?: "/tmp",
                arch = values["arch"].orEmpty(),
                mountRoot = values["mount"]?.takeIf { it.startsWith("/") } ?: WslPaths.DEFAULT_MOUNT_ROOT,
                currentSdk = values["current"]?.takeIf { it.startsWith("/") },
                tools = tools,
                networking = values["networking"]?.takeIf { it.isNotBlank() },
                env = env,
                osRelease = release.toString(),
                sdkJavaScript = when (values["sdkjs"]) {
                    "ok" -> true
                    "broken" -> false
                    else -> null
                },
            )
        }
    }
}

/** The WSL distributions installed, from `wsl.exe --list --verbose`. */
object WslDistributions {

    /** `wsl.exe` in System32, where Windows puts it; found by name on the PATH otherwise. */
    val EXE: String = System.getenv("SystemRoot")?.let { Path.of(it, "System32", "wsl.exe") }
        ?.takeIf { Files.isRegularFile(it) }?.toString() ?: "wsl.exe"

    data class Distribution(val name: String, val isDefault: Boolean, val version: Int?)

    /** Empty when WSL has none, or is not there at all; null when wsl.exe did not answer. */
    fun list(runner: WslToolHost.Runner = WslToolHost.Runner.PROCESS): List<Distribution>? {
        val output = runner.run(listOf(EXE, "--list", "--verbose"), LIST_MILLIS) ?: return null
        if (output.exitCode != 0) return emptyList()
        return parse(output.stdout)
    }

    /**
     * ```
     *   NAME            STATE           VERSION
     * * Ubuntu-24.04    Running         2
     *   docker-desktop  Stopped         2
     * ```
     */
    fun parse(text: String): List<Distribution> =
        text.lineSequence().map { it.replace("\u0000", "").trim() }.drop(1).filter { it.isNotEmpty() }.mapNotNull { line ->
            val isDefault = line.startsWith("*")
            val words = line.removePrefix("*").trim().split(Regex("\\s+"))
            val name = words.firstOrNull()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Distribution(name, isDefault, words.lastOrNull()?.toIntOrNull())
        }
            // Docker's are a container engine's, with no user and no shell to run an SDK in.
            .filterNot { it.name.startsWith("docker-desktop") || it.name.equals("rancher-desktop", ignoreCase = true) }
            .toList()

    private const val LIST_MILLIS = 15_000L
}

/** Running wsl.exe, without the IDE's process API — `sdk/` has none, so it can be tested without an IDE. */
internal object WslProcesses {

    fun run(words: List<String>, timeoutMillis: Long): WslToolHost.Output? {
        val process = runCatching {
            ProcessBuilder(words.map(::quoteForWindows))
                .apply { environment().putAll(WslToolHost.WSL_ENVIRONMENT) }
                .start()
        }.getOrNull() ?: return null
        process.outputStream.close()
        val stdout = CompletableFuture.supplyAsync { process.inputStream.readAllBytes().toString(Charsets.UTF_8) }
        val stderr = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().toString(Charsets.UTF_8) }
        if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        return WslToolHost.Output(process.exitValue(), stdout.get(5, TimeUnit.SECONDS), stderr.get(5, TimeUnit.SECONDS))
    }

    fun lastWords(output: WslToolHost.Output): String =
        (output.stderr + "\n" + output.stdout).lines().map { it.replace("\u0000", "").trim() }.lastOrNull { it.isNotEmpty() }.orEmpty()

    /**
     * One argument as `CreateProcess` reads it back: `ProcessBuilder` on Windows quotes an
     * argument with a space but leaves the quotes inside it alone, which the shell scripts here
     * are full of.
     */
    fun quoteForWindows(word: String): String {
        if (HostOs.current() != HostOs.WINDOWS) return word
        if (word.isNotEmpty() && word.none { it == ' ' || it == '\t' || it == '"' }) return word
        val quoted = StringBuilder("\"")
        var backslashes = 0
        for (c in word) {
            when (c) {
                '\\' -> backslashes++
                '"' -> {
                    quoted.append("\\".repeat(backslashes * 2 + 1)).append('"')
                    backslashes = 0
                }
                else -> {
                    quoted.append("\\".repeat(backslashes)).append(c)
                    backslashes = 0
                }
            }
        }
        return quoted.append("\\".repeat(backslashes * 2)).append('"').toString()
    }
}
