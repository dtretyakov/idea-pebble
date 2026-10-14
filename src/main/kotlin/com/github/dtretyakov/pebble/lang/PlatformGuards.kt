package com.github.dtretyakov.pebble.lang

/**
 * The calls in a C file that run on every platform: those no `PBL_` condition stands in front of.
 *
 * Pebble code says which platform it means in two ways, and both count as a guard here:
 *
 * - preprocessor regions — `#if defined(PBL_PLATFORM_EMERY)`, `#ifdef PBL_SPEAKER`, and the
 *   `#elif` and `#else` branches after one, since those are just as platform-specific;
 * - the SDK's choice macros — `PBL_IF_COLOR_ELSE(a, b)`, `PBL_PLATFORM_SWITCH(…)` — whose
 *   arguments are each meant for some platforms only.
 *
 * It is a lexer and not a preprocessor: comments and literals are skipped, a region is a guard if
 * its condition mentions `PBL_` at all, and macros the file defines are not expanded. A call
 * guarded by a macro of the project's own that wraps `PBL_` is therefore reported — rare in
 * practice, and suppressible like any inspection.
 */
object PlatformGuards {

    data class Call(val name: String, val start: Int, val end: Int)

    /** The calls to any of [names] in [text] that no `PBL_` condition guards. */
    fun unguardedCalls(text: CharSequence, names: Set<String>): List<Call> {
        val calls = mutableListOf<Call>()
        val regions = ArrayDeque<Boolean>()
        val parens = ArrayDeque<Boolean>()
        var atLineStart = true
        var choiceMacro = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\n' -> {
                    atLineStart = true
                    i++
                    continue
                }
                c == ' ' || c == '\t' || c == '\r' -> {
                    i++
                    continue
                }
                c == '#' && atLineStart -> {
                    val end = directiveEnd(text, i)
                    directive(text.substring(i + 1, end), regions)
                    i = end
                    continue
                }
                c == '/' && text.startsWith("//", i) -> {
                    i = lineEnd(text, i)
                    continue
                }
                c == '/' && text.startsWith("/*", i) -> {
                    i = text.indexOf("*/", i + 2).let { if (it < 0) text.length else it + 2 }
                    continue
                }
            }
            atLineStart = false
            when {
                c == '"' || c == '\'' -> {
                    i = literalEnd(text, i)
                    choiceMacro = false
                }
                c.isLetter() || c == '_' -> {
                    val start = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    val name = text.substring(start, i)
                    choiceMacro = name.startsWith("PBL_")
                    if (name in names && isCall(text, i) && !isMember(text, start) && regions.none { it } && parens.none { it }) {
                        calls += Call(name, start, i)
                    }
                }
                c == '(' -> {
                    parens.addLast(choiceMacro)
                    choiceMacro = false
                    i++
                }
                c == ')' -> {
                    parens.removeLastOrNull()
                    choiceMacro = false
                    i++
                }
                else -> {
                    choiceMacro = false
                    i++
                }
            }
        }
        return calls
    }

    private fun directive(line: String, regions: ArrayDeque<Boolean>) {
        val trimmed = line.trimStart()
        val keyword = trimmed.takeWhile { it.isLetter() }
        val condition = trimmed.substring(keyword.length)
        val mentionsPlatform = "PBL_" in condition
        when (keyword) {
            "if", "ifdef", "ifndef" -> regions.addLast(mentionsPlatform)
            "elif", "elifdef", "elifndef" -> if (regions.isNotEmpty()) regions.addLast(regions.removeLast() || mentionsPlatform)
            "endif" -> regions.removeLastOrNull()
        }
    }

    /** The end of a directive, past its continuation lines and with its comments still in. */
    private fun directiveEnd(text: CharSequence, start: Int): Int {
        var i = start
        while (i < text.length && text[i] != '\n') {
            if (text[i] == '\\' && i + 1 < text.length && text[i + 1] == '\n') i++
            i++
        }
        return i
    }

    private fun lineEnd(text: CharSequence, start: Int): Int =
        (start until text.length).firstOrNull { text[it] == '\n' } ?: text.length

    private fun literalEnd(text: CharSequence, start: Int): Int {
        val quote = text[start]
        var i = start + 1
        while (i < text.length && text[i] != quote && text[i] != '\n') {
            if (text[i] == '\\') i++
            i++
        }
        return minOf(i + 1, text.length)
    }

    private fun isCall(text: CharSequence, end: Int): Boolean {
        var i = end
        while (i < text.length && text[i].isWhitespace()) i++
        return i < text.length && text[i] == '('
    }

    /** `x.name(` and `x->name(` are a struct's function pointers, not the SDK's functions. */
    private fun isMember(text: CharSequence, start: Int): Boolean {
        var i = start - 1
        while (i >= 0 && text[i].isWhitespace()) i--
        return i >= 0 && (text[i] == '.' || (text[i] == '>' && i > 0 && text[i - 1] == '-'))
    }
}
