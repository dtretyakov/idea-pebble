package com.github.dtretyakov.pebble.debug.gdb

import java.io.ByteArrayOutputStream

/**
 * gdb's machine interface, read a line at a time: what `arm-none-eabi-gdb --interpreter=mi3`
 * prints, as records.
 *
 * ```
 * 12^done,bkpt={number="2",file="../src/c/main.c",line="12"}              a result, answering command 12
 * *stopped,reason="breakpoint-hit",frame={func="select_clicked",line="12"}  the target stopped
 * ~"Breakpoint 2, select_clicked (…) at ../src/c/main.c:12\n"            console text
 * (gdb)
 * ```
 *
 * The SDK's gdb has no Python, so no DAP: this is the protocol it does speak.
 */
sealed interface MiValue {
    data class Text(val text: String) : MiValue

    data class Tuple(val fields: List<Pair<String, MiValue>>) : MiValue {
        operator fun get(name: String): MiValue? = fields.firstOrNull { it.first == name }?.second
        fun text(name: String): String? = (get(name) as? Text)?.text
        fun tuple(name: String): Tuple? = get(name) as? Tuple
        fun list(name: String): List<MiValue> = (get(name) as? MiList)?.items.orEmpty()
    }

    /** A list of values, or of `name=value` results; the names of the latter are kept in [names]. */
    data class MiList(val items: List<MiValue>, val names: List<String?> = emptyList()) : MiValue

    companion object {
        val EMPTY = Tuple(emptyList())
    }
}

data class MiRecord(val kind: Kind, val token: Int?, val type: String, val results: MiValue.Tuple, val text: String? = null) {
    enum class Kind { RESULT, EXEC, STATUS, NOTIFY, CONSOLE, TARGET, LOG, PROMPT }

    fun text(name: String): String? = results.text(name)
    fun tuple(name: String): MiValue.Tuple? = results.tuple(name)
    fun list(name: String): List<MiValue> = results.list(name)

    val isError: Boolean get() = kind == Kind.RESULT && type == "error"
}

object MiParser {

    /** One line of gdb's output; null for an empty line, or one that is not MI. */
    fun parse(line: String): MiRecord? {
        val text = line.trimEnd('\r', '\n')
        if (text.isEmpty()) return null
        if (text.trim() == "(gdb)") return MiRecord(MiRecord.Kind.PROMPT, null, "", MiValue.EMPTY)
        val reader = Reader(text)
        val token = reader.token()
        val kind = when (reader.next()) {
            '^' -> MiRecord.Kind.RESULT
            '*' -> MiRecord.Kind.EXEC
            '+' -> MiRecord.Kind.STATUS
            '=' -> MiRecord.Kind.NOTIFY
            '~' -> return MiRecord(MiRecord.Kind.CONSOLE, token, "", MiValue.EMPTY, reader.string())
            '@' -> return MiRecord(MiRecord.Kind.TARGET, token, "", MiValue.EMPTY, reader.string())
            '&' -> return MiRecord(MiRecord.Kind.LOG, token, "", MiValue.EMPTY, reader.string())
            else -> return null
        }
        val type = reader.word()
        val results = mutableListOf<Pair<String, MiValue>>()
        while (reader.peek() == ',') {
            reader.next()
            results += reader.result()
        }
        return MiRecord(kind, token, type, MiValue.Tuple(results))
    }

    /** A C string as gdb reads a command's parameter: quoted, with backslashes, quotes and line breaks escaped. */
    fun quote(text: String): String =
        "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

    private class Reader(val s: String) {
        var i = 0

        fun peek(): Char? = s.getOrNull(i)
        fun next(): Char? = s.getOrNull(i++)

        fun token(): Int? {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i).toIntOrNull()
        }

        fun word(): String {
            val start = i
            while (i < s.length && s[i] != ',' && s[i] != '=' && s[i] != '}' && s[i] != ']') i++
            return s.substring(start, i)
        }

        fun result(): Pair<String, MiValue> {
            val name = word()
            if (peek() == '=') next()
            return name to value()
        }

        fun value(): MiValue = when (peek()) {
            '"' -> MiValue.Text(string())
            '{' -> {
                next()
                val fields = mutableListOf<Pair<String, MiValue>>()
                while (peek() != null && peek() != '}') {
                    fields += result()
                    if (peek() == ',') next()
                }
                next()
                MiValue.Tuple(fields)
            }
            '[' -> {
                next()
                val items = mutableListOf<MiValue>()
                val names = mutableListOf<String?>()
                while (peek() != null && peek() != ']') {
                    if (peek() == '"' || peek() == '{' || peek() == '[') {
                        items += value()
                        names += null
                    } else {
                        val (name, value) = result()
                        items += value
                        names += name
                    }
                    if (peek() == ',') next()
                }
                next()
                MiValue.MiList(items, names)
            }
            else -> MiValue.Text(word())
        }

        /**
         * A C string. gdb writes a byte outside ASCII as an octal escape, so the bytes are
         * gathered and read as UTF-8 at the end: `caf\303\251` is "café".
         */
        fun string(): String {
            if (peek() != '"') return s.substring(i).also { i = s.length }
            next()
            val out = ByteArrayOutputStream()
            fun append(c: Char) = out.write(c.toString().toByteArray(Charsets.UTF_8))
            while (i < s.length && s[i] != '"') {
                val c = s[i++]
                if (c != '\\' || i >= s.length) {
                    append(c)
                    continue
                }
                when (val e = s[i++]) {
                    'n' -> append('\n')
                    't' -> append('\t')
                    'r' -> append('\r')
                    'f' -> append('\u000c')
                    'a' -> append('\u0007')
                    'b' -> append('\b')
                    'v' -> append('\u000b')
                    'e' -> append('\u001b')
                    in '0'..'7' -> {
                        var code = e - '0'
                        var digits = 1
                        while (digits < 3 && i < s.length && s[i] in '0'..'7') {
                            code = code * 8 + (s[i++] - '0')
                            digits++
                        }
                        out.write(code and 0xFF)
                    }
                    else -> append(e)
                }
            }
            next()
            return out.toString(Charsets.UTF_8)
        }
    }
}
