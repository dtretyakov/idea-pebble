package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.run.LogLevel
import kotlinx.serialization.Serializable

@Serializable
data class LogLine(
    /** Increasing across runs: what to pass as `after` to read on from here. */
    val index: Long,
    val text: String,
    /** A watch log line's level; null for pebble-tool's own lines and the IDE's notes. */
    val level: String? = null,
    /** `app` for what pebble-tool prints, `error` for its errors, `ide` for the run's own notes. */
    val source: String,
)

@Serializable
data class LogPage(
    val lines: List<LogLine>,
    /** Where to read on from next time. */
    val next: Long,
    /** Lines after the cursor that no longer fit in the buffer, and are gone. */
    val dropped: Long = 0,
)

/**
 * The last lines of the Pebble runs' output, numbered, for reading on from a cursor: an agent
 * cannot watch a console that never ends, but it can ask what is new.
 */
class LogBuffer(private val capacity: Int = 5000) {

    enum class Source(val id: String) { APP("app"), ERROR("error"), IDE("ide") }

    private val lines = ArrayDeque<LogLine>()
    private val partial = mutableMapOf<Source, StringBuilder>()
    private var nextIndex = 0L

    /** The index the next line will have. */
    @get:Synchronized
    val position: Long get() = nextIndex

    /** Text as written, colours and all, split into lines; a line's end may come in a later call. */
    @Synchronized
    fun append(text: String, source: Source) {
        val buffer = partial.getOrPut(source) { StringBuilder() }
        buffer.append(text)
        var newline = buffer.indexOf("\n")
        while (newline >= 0) {
            add(buffer.substring(0, newline).trimEnd('\r'), source)
            buffer.delete(0, newline + 1)
            newline = buffer.indexOf("\n")
        }
    }

    /** What is left of an unfinished line, when its process has ended. */
    @Synchronized
    fun flush() {
        for ((source, buffer) in partial) if (buffer.isNotEmpty()) add(buffer.toString(), source).also { buffer.setLength(0) }
    }

    @Synchronized
    fun read(after: Long = -1, limit: Int = 200, minimum: LogLevel? = null, contains: String? = null): LogPage {
        val first = lines.firstOrNull()?.index ?: nextIndex
        val dropped = if (after + 1 < first) first - (after + 1) else 0
        val wanted = lines.asSequence()
            .filter { it.index > after }
            .filter { line -> minimum == null || line.level == null || LogLevel.valueOf(line.level) >= minimum }
            .filter { line -> contains.isNullOrEmpty() || line.text.contains(contains, ignoreCase = true) }
            .take(limit.coerceIn(1, 2000))
            .toList()
        val next = if (wanted.size == limit.coerceIn(1, 2000)) wanted.last().index else nextIndex - 1
        return LogPage(wanted, maxOf(next, after), dropped)
    }

    private fun add(raw: String, source: Source) {
        val level = if (source == Source.APP) LogLevel.ofLine(raw)?.name else null
        lines.addLast(LogLine(nextIndex++, ANSI.replace(raw, ""), level, source.id))
        while (lines.size > capacity) lines.removeFirst()
    }

    private companion object {
        val ANSI = Regex("\u001b\\[[0-9;?]*[A-Za-z]")
    }
}
