package com.github.dtretyakov.pebble.debug

import java.io.ByteArrayOutputStream

/**
 * What an Alloy app's JavaScript engine (Moddable's XS) says to its debugger, read from the bytes
 * pebble-tool relays from the watch.
 *
 * Each message is `<xsbug>…</xsbug>` with CRLFs around it, and holds one or more of: `login`;
 * `frames`, `local`, `global` and `grammar` (the view of a stopped machine); `break`; `log`;
 * `instruments` and `samples` (sent every second, unasked). The format is the engine's own,
 * `xsDebug.c` in the SDK's `toolchain/moddable`, and is almost XML: the engine writes control
 * characters as `&#10;`-style references, which XML forbids, so this is a small lenient reader
 * rather than an XML parser.
 */
class XsbugParser {

    private val buffer = ByteArrayOutputStream()

    /** The messages completed by [bytes], in order; a message cut between reads waits for the rest. */
    fun feed(bytes: ByteArray, length: Int = bytes.size): List<XsbugMessage> {
        buffer.write(bytes, 0, length)
        val messages = mutableListOf<XsbugMessage>()
        var data = buffer.toByteArray()
        while (true) {
            val end = indexOf(data, CLOSE)
            if (end < 0) break
            val start = indexOf(data, OPEN).takeIf { it in 0..end } ?: end
            // Decoded only now that the message is whole, so a character split between reads is intact.
            val text = String(data, start, end + CLOSE.size - start, Charsets.UTF_8)
            messages += XsbugMessage(XsbugXml.parse(text).children)
            data = data.copyOfRange(end + CLOSE.size, data.size)
        }
        buffer.reset()
        buffer.write(data)
        return messages
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        outer@ for (i in 0..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private companion object {
        val OPEN = "<xsbug>".toByteArray()
        val CLOSE = "</xsbug>".toByteArray()
    }
}

/** One element of a message: its attributes, child elements and text. */
class XsbugElement(val name: String, val attributes: Map<String, String>, val children: List<XsbugElement>, val text: String) {

    operator fun get(attribute: String): String? = attributes[attribute]

    fun child(name: String): XsbugElement? = children.firstOrNull { it.name == name }

    override fun toString(): String = "<$name $attributes>"
}

/** One message, with the parts a debugger acts on. */
class XsbugMessage(val elements: List<XsbugElement>) {

    val login: XsbugLogin?
        get() = element("login")?.let { XsbugLogin(it["name"].orEmpty(), it["value"].orEmpty()) }

    val frames: List<XsbugFrame>?
        get() = element("frames")?.children?.filter { it.name == "frame" }?.map(::frame)

    /** The selected frame's variables: what `select` and `toggle` answer with, and part of every break. */
    val local: XsbugScope?
        get() = element("local")?.let { XsbugScope(frame(it), it.children.map(::property)) }

    val global: List<XsbugProperty>?
        get() = element("global")?.children?.map(::property)

    val breakAt: XsbugBreak?
        get() = element("break")?.let { XsbugBreak(it["path"]?.ifEmpty { null }, it["line"]?.toIntOrNull(), it.text) }

    val logs: List<XsbugLog>
        get() = elements.filter { it.name == "log" }
            .map { XsbugLog(it.text, it["path"]?.ifEmpty { null }, it["line"]?.toIntOrNull()) }

    /** A reply to `select` or `toggle`: a view of the machine without a new break. */
    val isView: Boolean
        get() = breakAt == null && element("local") != null

    private fun element(name: String) = elements.firstOrNull { it.name == name }

    override fun toString(): String = elements.joinToString(prefix = "XsbugMessage(", postfix = ")") { it.name }

    private companion object {
        fun frame(element: XsbugElement) =
            XsbugFrame(element["name"].orEmpty(), element["value"].orEmpty(), element["path"]?.ifEmpty { null }, element["line"]?.toIntOrNull())

        fun property(element: XsbugElement): XsbugProperty = XsbugProperty(
            element["name"].orEmpty(),
            element["flags"].orEmpty(),
            element["value"],
            element["line"]?.toIntOrNull(),
            element.children.map(::property),
        )
    }
}

/** `<login name="main" value="XS 17.8.0 32-bit LE 16-bit ID"/>`: the machine, and the engine's version. */
data class XsbugLogin(val name: String, val version: String)

/** A stack frame. [id] is what `select` takes; a host frame has no [path]. */
data class XsbugFrame(val name: String, val id: String, val path: String?, val line: Int?)

/** A frame's variables. */
data class XsbugScope(val frame: XsbugFrame, val properties: List<XsbugProperty>)

/**
 * Where the machine stopped, and why: `# Break: breakpoint!`, `# Break: step!`, `# Break: debugger!`
 * for a `debugger` statement, or — breaking on exceptions — the exception's message.
 */
data class XsbugBreak(val path: String?, val line: Int?, val text: String) {
    val reason: String get() = text.trim().removePrefix("# Break:").trim().removeSuffix("!")
    val isException: Boolean get() = reason !in ORDINARY && !reason.startsWith("C: ")

    private companion object {
        val ORDINARY = setOf("breakpoint", "step", "debugger")
    }
}

/** Output of `console.log` and the engine's own reports; an exception carries where it was thrown. */
data class XsbugLog(val text: String, val path: String?, val line: Int?) {
    val isProblem: Boolean get() = text.startsWith("# Exception:") || text.startsWith("# Error:")
}

/**
 * A variable or property. [flags] begins with `+` when it can be expanded, `-` when it is
 * expanded (its [children] are here), and a space otherwise — a primitive, or an object already
 * shown higher up, whose [value] is then the dotted path to it.
 */
data class XsbugProperty(val name: String, val flags: String, val value: String?, val line: Int?, val children: List<XsbugProperty>) {
    val expandable: Boolean get() = flags.startsWith("+") || flags.startsWith("-")
    val expanded: Boolean get() = flags.startsWith("-")

    /** The object's address, which `toggle` takes. */
    val objectId: String? get() = value?.takeIf { expandable && it.startsWith("@") }

    /** A function's source: `value` is its file and [line] its line. */
    val sourcePath: String? get() = value?.takeIf { line != null && it.startsWith("/") }
}

/** The lenient reader behind [XsbugParser]: elements, attributes, text and character references. */
internal object XsbugXml {

    fun parse(text: String): XsbugElement {
        val reader = Reader(text)
        val roots = reader.content(until = null)
        return roots.singleOrNull() ?: XsbugElement("xsbug", emptyMap(), roots, "")
    }

    private class Reader(val s: String) {
        var i = 0

        /** Elements and text up to `</[until]>`, or the end. */
        fun content(until: String?): List<XsbugElement> {
            val elements = mutableListOf<XsbugElement>()
            val textOut = StringBuilder()
            while (i < s.length) {
                when {
                    s.startsWith("</", i) -> {
                        val end = s.indexOf('>', i).let { if (it < 0) s.length else it }
                        i = end + 1
                        if (until != null) {
                            lastText = textOut.toString()
                            return elements
                        }
                    }
                    s.startsWith("<?", i) -> i = s.indexOf("?>", i).let { if (it < 0) s.length else it + 2 }
                    s.startsWith("<![CDATA[", i) -> {
                        val end = s.indexOf("]]>", i).let { if (it < 0) s.length else it }
                        textOut.append(s, i + 9, end)
                        i = end + 3
                    }
                    s[i] == '<' -> elements += element()
                    s[i] == '&' -> textOut.append(reference())
                    else -> textOut.append(s[i++])
                }
            }
            lastText = textOut.toString()
            return elements
        }

        var lastText = ""

        fun element(): XsbugElement {
            i++ // <
            val name = name()
            val attributes = LinkedHashMap<String, String>()
            while (i < s.length) {
                skipSpace()
                when {
                    s.startsWith("/>", i) -> {
                        i += 2
                        return XsbugElement(name, attributes, emptyList(), "")
                    }
                    s[i] == '>' -> {
                        i++
                        val children = content(until = name)
                        return XsbugElement(name, attributes, children, lastText)
                    }
                    else -> {
                        val attribute = name()
                        skipSpace()
                        if (i < s.length && s[i] == '=') {
                            i++
                            skipSpace()
                            attributes[attribute] = quoted()
                        } else if (attribute.isEmpty()) {
                            i++
                        }
                    }
                }
            }
            return XsbugElement(name, attributes, emptyList(), "")
        }

        fun name(): String {
            val start = i
            while (i < s.length && !s[i].isWhitespace() && s[i] != '=' && s[i] != '>' && s[i] != '/' && s[i] != '"') i++
            return s.substring(start, i)
        }

        fun quoted(): String {
            if (i >= s.length || (s[i] != '"' && s[i] != '\'')) return name()
            val quote = s[i++]
            val out = StringBuilder()
            while (i < s.length && s[i] != quote) {
                if (s[i] == '&') out.append(reference()) else out.append(s[i++])
            }
            i++
            return out.toString()
        }

        fun reference(): String {
            val end = s.indexOf(';', i)
            if (end < 0 || end - i > 10) return s[i++].toString()
            val entity = s.substring(i + 1, end)
            i = end + 1
            return when {
                entity.startsWith("#x") || entity.startsWith("#X") -> entity.substring(2).toIntOrNull(16)?.let(::codePoint)
                entity.startsWith("#") -> entity.substring(1).toIntOrNull()?.let(::codePoint)
                else -> NAMED[entity]
            } ?: "&$entity;"
        }

        fun codePoint(code: Int): String? = runCatching { String(Character.toChars(code)) }.getOrNull()

        fun skipSpace() {
            while (i < s.length && s[i].isWhitespace()) i++
        }
    }

    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
}
