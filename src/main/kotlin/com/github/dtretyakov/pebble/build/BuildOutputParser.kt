package com.github.dtretyakov.pebble.build

import java.nio.file.Path

/** One diagnostic from a build, with its location resolved against the project. */
data class BuildMessage(
    val severity: Severity,
    val file: Path?,
    /** One-based, as gcc counts. */
    val line: Int?,
    /** One-based, as gcc counts. */
    val column: Int?,
    val text: String,
    /** The platform being built when it was reported; null when waf had not said. */
    val platform: String?,
) {
    enum class Severity { ERROR, WARNING }
}

/**
 * Reads what `pebble build` prints: waf's progress and gcc's diagnostics.
 *
 * waf runs the compiler from `build/`, so gcc names sources relative to it —
 * `../src/c/main.c:6:3: error: …` — and the paths are resolved against that directory, not the
 * project root. waf announces each step with the platform it belongs to
 * (`[13/16] Compiling gabbro | c: src/c/main.c -> …`), which is how a diagnostic is attributed to
 * a platform: gcc's own line does not say.
 *
 * Stateful, one instance per build: the platform carries from a progress line to the diagnostics
 * that follow it.
 */
class BuildOutputParser(projectRoot: Path) {

    private val buildDirectory = projectRoot.resolve("build")

    private var platform: String? = null

    /** The step waf last announced, as (done, total), or null before the first. */
    var progress: Pair<Int, Int>? = null
        private set

    fun parseLine(line: String): BuildMessage? {
        PROGRESS.find(line)?.let { match ->
            progress = match.groupValues[1].toInt() to match.groupValues[2].toInt()
            match.groupValues[3].takeIf { it.isNotEmpty() }?.let { platform = it }
            return null
        }

        DIAGNOSTIC.matchEntire(line)?.let { match ->
            val (file, lineNumber, column, kind, text) = match.destructured
            val severity = when (kind) {
                "warning" -> BuildMessage.Severity.WARNING
                "error", "fatal error" -> BuildMessage.Severity.ERROR
                else -> return null // notes belong to the diagnostic above them, and gcc prints them inline
            }
            return BuildMessage(severity, resolve(file), lineNumber.toInt(), column.toIntOrNull(), text, platform)
        }

        LINKER.matchEntire(line)?.let { match ->
            val (file, lineNumber, text) = match.destructured
            return BuildMessage(BuildMessage.Severity.ERROR, resolve(file), lineNumber.toInt(), null, text, platform)
        }

        return null
    }

    private fun resolve(file: String): Path? =
        runCatching { buildDirectory.resolve(file).normalize() }.getOrNull()

    private companion object {
        /** `[ 7/16] Compiling gabbro | c: …` — the platform is absent for steps shared by all of them. */
        val PROGRESS = Regex("""^\[\s*(\d+)/(\d+)]\s+\S+\s+(?:([a-z]+)\s+\|)?""")

        /** gcc: `file:line:column: severity: text`, the column optional. */
        val DIAGNOSTIC = Regex("""^(.+?):(\d+):(?:(\d+):)?\s+(fatal error|error|warning|note):\s+(.*)$""")

        /** ld: `file:line:(.text.main+0x4): undefined reference to 'foo'`. */
        val LINKER = Regex("""^(.+?\.[ch]):(\d+):\(.*?\):\s+(.*)$""")
    }
}
