package com.github.dtretyakov.pebble.debug

/**
 * What a debugger says to the engine: bare elements, each with a CRLF before and after — the
 * engine acts on a command only once the line after it has ended.
 *
 * The engine reads attribute values as they are, without references, so a value cannot hold a
 * double quote or a line break; such a path is refused here rather than garbled there. Ids are
 * the `@`-prefixed upper-case hex addresses the engine sent.
 */
object XsbugCommands {

    /**
     * A breakpoint, and what it does when reached: stop only if [condition] holds, only on the
     * passes [hitCount] allows (`>=3`, `%2`, …), or — with a [trace] — log that expression's value
     * and go on. [condition] and [trace] are bytecode from [XscCompiler], in hex.
     */
    data class Breakpoint(
        val path: String,
        val line: Int,
        val condition: String? = null,
        val hitCount: String? = null,
        val trace: String? = null,
    ) {
        val hasOptions: Boolean get() = condition != null || hitCount != null || trace != null

        /** Where it is, which is what the engine clears by. */
        val location: Breakpoint get() = Breakpoint(path, line)
    }

    /** Breaks where an exception is thrown, as an entry of the breakpoint list. */
    val EXCEPTIONS = Breakpoint("exceptions", 0)

    fun go(): ByteArray = command("<go/>")

    fun step(): ByteArray = command("<step/>")

    fun stepInside(): ByteArray = command("<step-inside/>")

    fun stepOutside(): ByteArray = command("<step-outside/>")

    /**
     * Stops at the next JavaScript statement the app runs. `step-inside` while running marks the
     * frames the engine is in; `step` would not stop, and the `start` breakpoint stops in a host
     * frame with no source to show.
     */
    fun pause(): ByteArray = stepInside()

    /** Disconnects, and lets the app run on: the watch is left as if no debugger had been there. */
    fun logout(): ByteArray = command("<logout/>")

    fun setBreakpoint(breakpoint: Breakpoint): ByteArray {
        val position = """path="${value(breakpoint.path)}" line="${breakpoint.line}""""
        if (!breakpoint.hasOptions) return command("""<set-breakpoint $position id="@0"/>""")
        return command(
            buildString {
                append("""<set-breakpoint $position id="@1">""")
                breakpoint.condition?.let { append("""<breakpoint-condition path="${code(it)}"/>""") }
                breakpoint.hitCount?.let { append("""<breakpoint-hit-count path="${value(it)}"/>""") }
                breakpoint.trace?.let { append("""<breakpoint-trace path="${code(it)}"/>""") }
                append("</set-breakpoint>")
            },
        )
    }

    fun clearBreakpoint(breakpoint: Breakpoint): ByteArray =
        command("""<clear-breakpoint path="${value(breakpoint.path)}" line="${breakpoint.line}" id="@0"/>""")

    /**
     * The whole list at once, which the engine expects right after login; it adds to what it has.
     * Plain breakpoints only: one with a condition in this list makes the app fail to start
     * ("failed to allocate XS machine"), so those follow one by one with [setBreakpoint].
     */
    fun setAllBreakpoints(breakpoints: Collection<Breakpoint>): ByteArray {
        require(breakpoints.none { it.hasOptions }) { "Breakpoints with conditions are set one by one" }
        return command(
            breakpoints.joinToString("", "<set-all-breakpoints>", "</set-all-breakpoints>") {
                """<breakpoint path="${value(it.path)}" line="${it.line}"/>"""
            },
        )
    }

    /** Evaluates compiled [code] in a frame; the engine answers with `<result line="[index]">` and a view. */
    fun eval(frameId: String, code: String, index: Int): ByteArray =
        command("""<eval id="${id(frameId)}" path="${code(code)}" line="$index"/>""")

    /** Shows a frame's variables; the engine answers with a view. */
    fun select(frameId: String): ByteArray = command("""<select id="${id(frameId)}"/>""")

    /** Expands an object, or folds it if expanded; the engine answers with a view. */
    fun toggle(objectId: String): ByteArray = command("""<toggle id="${id(objectId)}"/>""")

    /** Whether the engine can be given [path]: no double quote and no line break. */
    fun canSend(path: String): Boolean = path.none { it == '"' || it == '\r' || it == '\n' }

    private fun value(text: String): String {
        require(canSend(text)) { "The debugger cannot be sent: $text" }
        return text
    }

    private fun code(hex: String): String {
        require(HEX.matches(hex)) { "Not compiled code" }
        return hex
    }

    private fun id(text: String): String {
        val id = "@" + text.removePrefix("@").uppercase()
        require(ID.matches(id)) { "Not an object id: $text" }
        return id
    }

    private fun command(element: String): ByteArray = "\r\n$element\r\n".toByteArray(Charsets.UTF_8)

    private val ID = Regex("@[0-9A-F]+")
    private val HEX = Regex("[0-9A-F]+")
}
