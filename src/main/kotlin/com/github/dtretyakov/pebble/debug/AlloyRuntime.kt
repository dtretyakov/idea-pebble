package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.sdk.PebbleSdk
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readLines

/**
 * The Alloy runtime's own JavaScript, for the frames of it a stop passes through.
 *
 * Between an app's handler and the firmware are the runtime's functions — `Pebble.prototype.do`,
 * which hands `watch` events to their listeners. The engine runs them from the firmware, compiled
 * without debugging information, and names their frames but gives them no file or line: the
 * debugger could show only a grey name, and nothing of the code that called the app. The SDK ships
 * that code, in `toolchain/moddable/build/devices/pebble/modules`, so a frame is found there by
 * the engine's name for its function.
 *
 * The line is the best the source can say without the engine: in the function's body, the first
 * line naming the function the frame called — `forEach` in `do` — and otherwise the line the
 * function is declared on.
 */
object AlloyRuntime {

    data class Location(val file: Path, val line: Int)

    /** Where [name] runs, given the name of the frame it called; null for a function not there. */
    fun locate(sdk: PebbleSdk?, name: String, callee: String?): Location? {
        val modules = sdk?.toolchain?.resolve(MODULES)?.takeIf { it.isDirectory() } ?: return null
        for (file in sources(modules)) {
            val lines = cache.getOrPut(file) { runCatching { file.readLines() }.getOrDefault(emptyList()) }
            val body = definitions(lines)[name] ?: continue
            return Location(file, callSite(lines, body, callee))
        }
        return null
    }

    /**
     * Functions by the engine's names for them, with the lines of their bodies, 0-based: a class's
     * method `C.prototype.m`, a static one `C.m`, a function `f`.
     */
    fun definitions(lines: List<String>): Map<String, IntRange> {
        val found = LinkedHashMap<String, IntRange>()
        var currentClass: String? = null
        var classDepth = 0
        var depth = 0
        lines.forEachIndexed { index, line ->
            val code = line.substringBefore("//")
            CLASS.find(code)?.let { if (depth == 0) { currentClass = it.groupValues[1]; classDepth = depth } }
            val function = FUNCTION.find(code)?.groupValues?.get(1)
            val method = if (currentClass != null && depth == classDepth + 1) METHOD.find(code) else null
            val key = when {
                function != null -> function
                method != null && method.groupValues[2] !in NOT_METHODS ->
                    if (method.groupValues[1].isNotBlank()) "$currentClass.${method.groupValues[2]}" else "$currentClass.prototype.${method.groupValues[2]}"
                else -> null
            }
            if (key != null && key !in found) found[key] = index..bodyEnd(lines, index)
            depth += code.count { it == '{' } - code.count { it == '}' }
            if (currentClass != null && depth <= classDepth && index > 0 && '}' in code) currentClass = null
        }
        return found
    }

    private fun callSite(lines: List<String>, body: IntRange, callee: String?): Int {
        val short = callee?.substringAfterLast('.')?.takeIf { it.isNotBlank() && !it.startsWith("(") } ?: return body.first
        val call = Regex("""\b${Regex.escape(short)}\s*\(""")
        return (body.first + 1..body.last).firstOrNull { call.containsMatchIn(lines[it]) } ?: body.first
    }

    private fun bodyEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var opened = false
        for (index in start until lines.size) {
            val code = lines[index].substringBefore("//")
            for (char in code) {
                if (char == '{') { depth++; opened = true }
                if (char == '}') depth--
            }
            if (opened && depth <= 0) return index
        }
        return lines.lastIndex
    }

    private fun sources(modules: Path): List<Path> = sourceLists.getOrPut(modules) {
        Files.walk(modules).use { paths -> paths.filter { it.extension == "js" }.sorted().toList() }
    }

    private val cache = ConcurrentHashMap<Path, List<String>>()
    private val sourceLists = ConcurrentHashMap<Path, List<Path>>()

    private const val MODULES = "moddable/build/devices/pebble/modules"
    private val CLASS = Regex("""^\s*(?:export\s+)?(?:default\s+)?class\s+(\w+)""")
    private val FUNCTION = Regex("""^\s*(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*(\w+)\s*\(""")
    private val METHOD = Regex("""^\s*(static\s+)?(?:async\s+)?\*?\s*(#?\w+)\s*\([^)]*\)\s*\{""")
    private val NOT_METHODS = setOf("if", "for", "while", "switch", "catch", "with", "return", "function")
}
