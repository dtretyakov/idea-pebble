package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PlatformStubs
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/** What the SDK says about a name: where it is declared, as what, and where it is not really there. */
@Serializable
data class ApiLookup(
    val symbol: String,
    /** One per distinct declaration, with the platforms that declare it so. */
    val declarations: List<ApiDeclaration>,
    /** Platforms whose `pebble.h` has the name only as a macro that does nothing there. */
    val stubbedOn: List<ApiStub> = emptyList(),
    /** Platforms whose `pebble.h` does not have the name at all. */
    val missingOn: List<String> = emptyList(),
    /** Names in the SDK most like this one, when it has no such name. */
    val suggestions: List<String> = emptyList(),
)

@Serializable
data class ApiDeclaration(
    val language: String,
    val declaration: String,
    val documentation: String? = null,
    /** C: the platforms; Alloy: the module. */
    val platforms: List<String> = emptyList(),
    val module: String? = null,
    /** Alloy: how a module brings it in. */
    val import: String? = null,
)

@Serializable
data class ApiStub(val platform: String, val expansion: String)

/**
 * The Pebble API, looked up in the SDK an app builds against — rather than remembered: what a
 * model recalls is SDK 3's, and whether a call exists, or is a stub on aplite or chalk, is written
 * only in each platform's `pebble.h`.
 *
 * C comes from `sdk-core/pebble/<platform>/include/pebble.h`, whose `//!` comments are the SDK's
 * documentation; Alloy from the SDK's TypeScript typings, `toolchain/moddable/typings`.
 */
object PebbleApi {

    fun lookup(sdk: PebbleSdk, symbol: String, platforms: List<String>): ApiLookup {
        val name = symbol.trim().removeSuffix("()")
        val headers = platforms.associateWith { platform ->
            sdk.includeDir(platform).resolve("pebble.h").takeIf { it.isRegularFile() }?.let { runCatching { it.readText() }.getOrNull() }
        }.filterValues { it != null }.mapValues { it.value!! }

        val found = linkedMapOf<Pair<String, String?>, MutableList<String>>()
        val stubs = mutableListOf<ApiStub>()
        val missing = mutableListOf<String>()
        for ((platform, header) in headers) {
            val stub = PlatformStubs.parse(header)[name]
            if (stub != null && PlatformStubs.isInert(stub)) {
                stubs += ApiStub(platform, stub)
                continue
            }
            val declaration = declaration(header, name)
            if (declaration == null) missing += platform else found.getOrPut(declaration) { mutableListOf() } += platform
        }
        val declarations = found.map { (declaration, on) -> ApiDeclaration("c", declaration.first, declaration.second, on) } +
            alloy(sdk.toolchain.resolve("moddable/typings"), name)
        val suggestions = if (declarations.isEmpty() && stubs.isEmpty()) suggest(name, headers.values.firstOrNull().orEmpty()) else emptyList()
        return ApiLookup(name, declarations, stubs, if (declarations.any { it.language == "c" } || stubs.isNotEmpty()) missing else emptyList(), suggestions)
    }

    /** The declaration of [name] in a C header, with the `//!` comment above it; null when there is none. */
    fun declaration(header: String, name: String): Pair<String, String?>? {
        val lines = header.lines()
        val id = Regex.escape(name)
        val define = Regex("""^\s*#\s*define\s+$id\b""")
        val call = Regex("""^[A-Za-z_][\w\s*]*?\b$id\s*\(""")
        val typedef = Regex("""^\s*(typedef\b.*\b$id\s*;|}\s*$id\s*;|(struct|enum|union)\s+$id\s*\{)""")
        val value = Regex("""^\s*$id\s*(=[^=].*)?,?\s*(//.*)?$""")
        for ((index, line) in lines.withIndex()) {
            val start = when {
                define.containsMatchIn(line) -> index
                call.containsMatchIn(line) && !line.trimStart().startsWith("return") -> index
                typedef.containsMatchIn(line) -> typedefStart(lines, index)
                value.matches(line) && insideEnum(lines, index) -> index
                else -> continue
            }
            val end = when {
                define.containsMatchIn(line) -> continuation(lines, index)
                call.containsMatchIn(line) -> (index until minOf(lines.size, index + 12)).firstOrNull { lines[it].contains(';') || lines[it].contains('{') } ?: index
                else -> index
            }
            val text = lines.subList(start, end + 1).joinToString("\n").trimEnd().let { if (it.lines().size > 40) it.lines().take(40).joinToString("\n") + "\n…" else it }
            return text to documentation(lines, start, line)
        }
        return null
    }

    /** The SDK's names most like [name]: sharing most of its words, then fewest edits away. */
    fun suggest(name: String, header: String, limit: Int = 8): List<String> {
        val names = NAMES.findAll(header).mapNotNull { match -> match.groupValues.drop(1).firstOrNull { it.isNotEmpty() } }
            .filter { it.length > 2 && !it.startsWith("_") }.toSet()
        val words = words(name).toSet()
        return names.filter { it != name }
            .map { candidate -> Triple(candidate, words(candidate).count { it in words }, distance(name.lowercase(), candidate.lowercase())) }
            .filter { it.second > 0 || it.third <= 3 }
            .sortedWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
            .take(limit).map { it.first }
    }

    /** `text_layer_set_text` and `GTextAlignmentCenter` as words: text, layer, set, text; gtext, alignment, center. */
    private fun words(name: String): List<String> =
        name.split('_').flatMap { it.split(Regex("(?<=[a-z0-9])(?=[A-Z])")) }.filter { it.isNotEmpty() }.map { it.lowercase() }

    private fun alloy(typings: Path, name: String): List<ApiDeclaration> {
        if (!Files.isDirectory(typings)) return emptyList()
        val id = Regex.escape(name)
        val declared = Regex("""\b(class|interface|type|function|const|let|var|enum|namespace)\s+$id\b|^\s*(static\s+|readonly\s+|get\s+|set\s+)*$id\s*[(:<?]""")
        val moduleNamed = Regex("""declare module "([^"]*\b$id\b[^"]*)"""")
        val defaultExport = Regex("""^\s*export\s+default\s+$id\b""")
        val results = mutableListOf<ApiDeclaration>()
        val modules = linkedSetOf<String>()
        Files.walk(typings).use { paths ->
            paths.filter { it.isRegularFile() && it.toString().endsWith(".d.ts") }.sorted().forEach { file ->
                val lines = runCatching { file.readText() }.getOrNull()?.lines() ?: return@forEach
                var module: String? = null
                val found = mutableListOf<Pair<Int, String?>>()
                var exportedByDefault = false
                for ((index, line) in lines.withIndex()) {
                    MODULE.find(line)?.let { module = it.groupValues[1] }
                    moduleNamed.find(line)?.let { modules += it.groupValues[1] }
                    if (defaultExport.containsMatchIn(line)) exportedByDefault = true
                    if (declared.containsMatchIn(line) && !line.trimStart().startsWith("import")) found += index to module
                }
                for ((index, inModule) in found) {
                    val where = inModule ?: typings.relativize(file).invariantSeparatorsPathString.removeSuffix(".d.ts")
                    val exported = lines[index].trimStart().startsWith("export")
                    val import = when {
                        inModule == null -> null
                        exportedByDefault -> "import $name from \"$inModule\""
                        exported -> "import { $name } from \"$inModule\""
                        else -> null
                    }
                    results += ApiDeclaration("alloy", lines[index].trim(), jsDoc(lines, index), module = where, import = import)
                }
            }
        }
        val distinct = results.distinctBy { it.module to it.declaration }.take(10)
        return distinct.ifEmpty { modules.take(5).map { ApiDeclaration("alloy", "declare module \"$it\"", module = it, import = "import $name from \"$it\"") } }
    }

    private fun typedefStart(lines: List<String>, end: Int): Int {
        if (lines[end].trimStart().startsWith("typedef")) return end
        var depth = 0
        for (i in end downTo maxOf(0, end - 200)) {
            depth += lines[i].count { it == '}' } - lines[i].count { it == '{' }
            if (depth <= 0 && lines[i].contains('{')) return i
        }
        return end
    }

    private fun insideEnum(lines: List<String>, index: Int): Boolean {
        for (i in index - 1 downTo maxOf(0, index - 300)) {
            val line = lines[i].trim()
            if (line.startsWith("}")) return false
            if (line.contains("enum") && line.contains('{')) return true
            if (line.endsWith(";") && !line.startsWith("//")) return false
        }
        return false
    }

    private fun continuation(lines: List<String>, index: Int): Int {
        var end = index
        while (end + 1 < lines.size && lines[end].trimEnd().endsWith("\\")) end++
        return end
    }

    /** The `//!` lines just above [start], or the `//!<` comment at the end of [line]. */
    private fun documentation(lines: List<String>, start: Int, line: String): String? {
        val above = mutableListOf<String>()
        var i = start - 1
        while (i >= 0 && lines[i].trimStart().startsWith("//!")) {
            above += lines[i].trimStart().removePrefix("//!").removePrefix(" ")
            i--
        }
        val trailing = line.substringAfter("//!<", "").trim().takeIf { it.isNotEmpty() }
        return above.reversed().joinToString("\n").trim().ifEmpty { trailing }
    }

    private fun jsDoc(lines: List<String>, index: Int): String? {
        if (index == 0 || !lines[index - 1].trim().endsWith("*/")) return null
        val start = (index - 1 downTo maxOf(0, index - 60)).firstOrNull { lines[it].trim().startsWith("/**") } ?: return null
        return lines.subList(start, index).joinToString("\n") { it.trim().removePrefix("/**").removePrefix("*/").removePrefix("*").trim() }.trim().ifEmpty { null }
    }

    private fun distance(a: String, b: String): Int {
        val row = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = row[0]
            row[0] = i
            for (j in 1..b.length) {
                val current = row[j]
                row[j] = minOf(row[j] + 1, row[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = current
            }
        }
        return row[b.length]
    }

    /** Declared names, one line at a time: macros, functions, the ends of typedefs and enum values. */
    private val NAMES = Regex("""(?m)^[ \t]*#[ \t]*define[ \t]+(\w+)|^[A-Za-z_][\w \t*]*?\b(\w+)[ \t]*\(|}[ \t]*(\w+)[ \t]*;|^[ \t]*typedef\b[^;\n]*\b(\w+)[ \t]*;|^[ \t]+([A-Z]\w+)[ \t]*[,=]""")
    private val MODULE = Regex("""declare module "([^"]+)"""")
}
