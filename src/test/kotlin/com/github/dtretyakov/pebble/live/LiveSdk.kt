package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.project.ToolHosts
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.HostTool
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.PebbleTool
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.github.dtretyakov.pebble.sdk.WslDistributions
import com.github.dtretyakov.pebble.sdk.WslProcesses
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyToRecursively

/**
 * The real pebble-tool, for the tests that check the plugin's reading of it against what it does.
 *
 * Opt-in with `./gradlew test -PliveTests`: on a machine without pebble-tool these would be
 * reporting the machine, not the code. On Windows, the pebble-tool of WSL's default distribution
 * (or the one `PEBBLE_WSL_DISTRIBUTION` names), run the way the plugin runs it.
 */
object LiveSdk {

    /** Where the tools run for these tests: this machine, or on Windows a WSL distribution. */
    val host: ToolHost by lazy {
        if (HostOs.current() != HostOs.WINDOWS) return@lazy LocalToolHost()
        val name = System.getenv("PEBBLE_WSL_DISTRIBUTION")?.takeIf { it.isNotBlank() }
            ?: WslDistributions.list()?.let { list -> (list.firstOrNull { it.isDefault } ?: list.firstOrNull())?.name }
            ?: "Ubuntu"
        WslToolHost(name).also {
            it.refresh()
            // For the tests without an IDE, where the plugin cannot list WSL's distributions or
            // read its settings: the plugin's code runs its commands where these tests do.
            if (ApplicationManager.getApplication() == null) ToolHosts.useHost(it, Disposer.newDisposable("live tests"))
        }
    }

    fun tool(): Path {
        assumeTrue(System.getProperty("pebble.liveTests") == "true", "live tests are off; run with -PliveTests")
        val tool = if (host is LocalToolHost) PebbleTool.locate("", System.getenv("PATH")) else host.locate(HostTool.PEBBLE)
        assumeTrue(tool != null, "pebble-tool is not installed")
        return tool!!
    }

    /** pebble-tool's data directory where it runs, as the IDE reads it. */
    fun dataDir(): Path? = PebbleDataDir.locate(host)

    /** The SDKs installed there, as the plugin reads them. */
    fun sdks(): PebbleSdks = PebbleSdks.scan(dataDir(), host::resolveLink)

    /** A fresh copy of a fixture project, so a build never writes into the repository. */
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun fixture(name: String): Path {
        val source = Path.of(LiveSdk::class.java.getResource("/$name/package.json")!!.toURI()).parent
        val copy = Files.createTempDirectory("pebble-live").resolve(name)
        source.copyToRecursively(copy, followLinks = false, overwrite = false)
        return copy
    }

    class Output(val exitCode: Int, val text: String)

    /**
     * `pebble install`, once more if the first attempt met an emulator still starting: pebble-tool
     * waits about five seconds for it, and a cold start here can take longer.
     */
    fun install(directory: Path, vararg arguments: String): Output {
        val first = run(directory, "install", *arguments)
        if (first.exitCode == 0 || "Connection refused" !in first.text) return first
        return run(directory, "install", *arguments)
    }

    fun run(directory: Path, vararg arguments: String): Output =
        exec(tool(), arguments.toList(), directory, mapOf("PYTHONWARNINGS" to "ignore"))

    /** Any of the tools' programs, run the way the plugin runs them. */
    fun exec(executable: Path, arguments: List<String>, directory: Path, environment: Map<String, String> = emptyMap()): Output {
        val command = host.command(executable, arguments, directory, environment)
        val process = ProcessBuilder(command.words.map(WslProcesses::quoteForWindows))
            .apply { command.workDirectory?.let { directory(it.toFile()) } }
            .redirectErrorStream(true)
            .apply { environment().putAll(command.environment) }
            .start()
        val text = process.inputStream.bufferedReader().readText()
        check(process.waitFor(10, TimeUnit.MINUTES)) { "pebble ${arguments.joinToString(" ")} did not finish" }
        return Output(process.exitValue(), text)
    }
}
