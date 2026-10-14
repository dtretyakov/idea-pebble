package com.github.dtretyakov.pebble.debug

import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Which files an Alloy app's JavaScript engine runs, and how it names them.
 *
 * mcrun compiles `src/embeddedjs/` — JavaScript, and TypeScript through tsc, which the engine
 * maps back to the `.ts` through its source map — and gives xsc each file's absolute path. That
 * path is the engine's name for the file: breakpoints are set by it and breaks report it. It is
 * the path as the build saw it, which is the real one, without symbolic links.
 */
object AlloySources {

    const val DIRECTORY = "src/embeddedjs"

    private val EXTENSIONS = setOf("js", "mjs", "ts")

    fun isAlloySource(root: Path, file: Path): Boolean =
        file.extension in EXTENSIONS && enginePath(file).startsWith(enginePath(root).resolve(DIRECTORY))

    /** The engine's name for [file]. */
    fun enginePath(file: Path): Path = runCatching { file.toRealPath() }.getOrElse { file.toAbsolutePath().normalize() }
}

/**
 * Finding a value in the engine's views.
 *
 * The engine keeps which objects are expanded, and every `select` and `toggle` answers with the
 * whole frame re-rendered, expanded objects with their properties nested. A value is found again
 * by the path of names and object ids that led to it; on the way, an object not yet expanded is
 * the next one to `toggle`.
 */
object XsbugTree {

    data class Step(val name: String, val id: String)

    sealed interface Lookup {
        data class Found(val property: XsbugProperty) : Lookup
        data class Collapsed(val id: String) : Lookup
        data object Gone : Lookup
    }

    fun find(properties: List<XsbugProperty>, path: List<Step>): Lookup {
        var level = properties
        for ((index, step) in path.withIndex()) {
            val property = level.firstOrNull { it.name == step.name && it.objectId == step.id } ?: return Lookup.Gone
            if (index == path.lastIndex) {
                return if (property.expandable && !property.expanded) Lookup.Collapsed(step.id) else Lookup.Found(property)
            }
            if (!property.expanded) return Lookup.Collapsed(step.id)
            level = property.children
        }
        return Lookup.Gone
    }
}

/** How a value reads in the debugger: the engine sends it as text, already formatted. */
object XsbugValues {

    enum class Kind { STRING, NUMBER, KEYWORD, OBJECT, FUNCTION, UNINITIALIZED, OTHER }

    data class Shown(val kind: Kind, val text: String)

    fun of(property: XsbugProperty): Shown {
        val value = property.value ?: return Shown(Kind.UNINITIALIZED, "not initialized")
        return when {
            property.sourcePath != null || value == "(host function)" || value == "(XS code)" || value == "(C code)" ->
                Shown(Kind.FUNCTION, "function")
            value.length >= 2 && value.startsWith("'") && value.endsWith("'") -> Shown(Kind.STRING, value.substring(1, value.length - 1))
            value in KEYWORDS -> Shown(Kind.KEYWORD, value)
            NUMBER.matches(value) -> Shown(Kind.NUMBER, value)
            property.objectId != null -> Shown(Kind.OBJECT, "")
            else -> Shown(Kind.OTHER, value)
        }
    }

    private val KEYWORDS = setOf("undefined", "null", "true", "false", "NaN", "Infinity", "-Infinity")
    private val NUMBER = Regex("""-?\d+(\.\d+)?([eE][+-]?\d+)?n?""")
}
