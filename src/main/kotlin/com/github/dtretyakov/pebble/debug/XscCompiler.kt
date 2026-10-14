package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.sdk.PebbleSdk
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
class XscCompiler(private val xsc: Path) : ExpressionCompiler {

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
            val process = ProcessBuilder(xsc.toString(), source.toString(), "-p", "-d", "-eval", "-e", "-o", directory.toString(), "-r", "expression")
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
        fun forSdk(sdk: PebbleSdk): XscCompiler? =
            sdk.toolchain.resolve("moddable-tools/xsc").takeIf(Files::isExecutable)?.let(::XscCompiler)

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
