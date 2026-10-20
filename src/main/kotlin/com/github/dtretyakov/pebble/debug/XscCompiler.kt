package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.github.dtretyakov.pebble.sdk.WslProcesses
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText

/**
 * Expressions for the engine: it evaluates bytecode, not source, so an expression to evaluate,
 * a breakpoint's condition or what it logs is compiled on this machine by the SDK's own `xsc`,
 * as Moddable's xsbug-log does it — `xsc expr.js -p -d -eval -e` — and sent as hex.
 */
class XscCompiler(
    private val xsc: Path,
    /** Where xsc runs: under WSL, `xsc` is a Linux program and is given Linux paths. */
    private val host: ToolHost = LocalToolHost(),
) : ExpressionCompiler {

    sealed interface Result {
        data class Compiled(val hex: String) : Result
        data class Failed(val message: String) : Result
    }

    private val cache = ConcurrentHashMap<String, Result>()

    override fun compile(expression: String): Result = cache.getOrPut(expression) { run(expression) }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun run(expression: String): Result {
        val directory = Files.createTempDirectory("xsc")
        try {
            val source = directory.resolve("expression.js").apply { writeText(expression) }
            // The temporary directory is the IDE's; under WSL the distribution reaches it as /mnt/c/….
            val spell = { path: Path -> host.toolPath(path) ?: path.toString() }
            val command = host.command(xsc, listOf(spell(source), "-p", "-d", "-eval", "-e", "-o", spell(directory), "-r", "expression"), null)
            val process = ProcessBuilder(command.words.map(WslProcesses::quoteForWindows))
                .apply { environment().putAll(command.environment) }
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Result.Failed("xsc did not finish")
            }
            val compiled = directory.resolve("expression.xsb")
            if (process.exitValue() != 0 || !Files.isRegularFile(compiled)) return Result.Failed(message(output))
            return Result.Compiled(Files.readAllBytes(compiled).joinToString("") { "%02X".format(it) })
        } finally {
            runCatching { directory.deleteRecursively() }
        }
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10L

        /** The SDK's `xsc`; null in an SDK without Alloy's tools. */
        fun forSdk(sdk: PebbleSdk, host: ToolHost = LocalToolHost()): XscCompiler? =
            sdk.toolchain.resolve("moddable-tools/xsc")
                .takeIf { if (host is LocalToolHost) Files.isExecutable(it) else Files.isRegularFile(it) }
                ?.let { XscCompiler(it, host) }

        /** xsc's `# error: missing )!` lines, as one sentence. */
        fun message(output: String): String =
            output.lines().map { it.trim() }
                .filter { it.startsWith("# error:") || it.contains(" error:") }
                .map { it.substringAfter("error:").trim().removeSuffix("!") }
                .ifEmpty { listOf(output.trim().ifEmpty { "xsc could not compile it" }) }
                .joinToString("; ")
    }
}

/** Turns an expression into what the engine runs; [XscCompiler] in an IDE, anything in a test. */
fun interface ExpressionCompiler {
    fun compile(expression: String): XscCompiler.Result
}
