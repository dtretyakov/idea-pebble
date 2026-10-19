package com.github.dtretyakov.pebble.build

import java.util.Locale

/**
 * How much of a watch the app takes, per platform: what `pebble build` reports after linking each
 * one (the SDK's `memory_reports.py`), read back so the Build window can say it under that
 * platform rather than leave it in the output.
 *
 * ```
 * -------------------------------------------------------
 * EMERY APP MEMORY USAGE
 * Total size of resources:        4092 bytes / 256.0KB
 * Total footprint in RAM:         220 bytes / 128.0KB
 * Free RAM available (heap):      130852 bytes
 * -------------------------------------------------------
 * ```
 *
 * The limits are the SDK's own, from the report: RAM is the platform's app memory, and the
 * resource limit is the one the app store accepts.
 */
data class MemoryUsage(
    val platform: String,
    /** `app`, `worker` or `lib`. */
    val binary: String,
    val ram: Long,
    val ramLimit: Long?,
    val heapFree: Long?,
    val resources: Long?,
    val resourceLimit: Long?,
) {
    /** Little heap left, or resources the store would refuse. */
    val isTight: Boolean
        get() = (heapFree != null && ramLimit != null && heapFree * 10 < ramLimit) ||
            (resources != null && resourceLimit != null && resources > resourceLimit)

    val summary: String
        get() = buildString {
            // The Build window files each under its platform's group, but its tree does not show
            // groups: without the name, seven platforms read as seven identical lines.
            append(if (binary == "app") "Memory" else "${binary.replaceFirstChar { it.uppercase() }} memory")
            append(" on ").append(platform)
            append(": ").append(size(ram)).append(" in RAM")
            ramLimit?.let { append(" of ").append(size(it)) }
            heapFree?.let { append(", ").append(size(it)).append(" free for the heap") }
            resources?.let {
                append("; resources ").append(size(it))
                resourceLimit?.let { limit -> append(" of the store's ").append(size(limit)) }
            }
        }

    private companion object {
        fun size(bytes: Long): String =
            if (bytes < 1024) "$bytes bytes" else String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
    }
}

/** Reads the reports out of a build's output, a line at a time. One instance per build. */
class MemoryReportParser {

    private var current: Builder? = null

    /** The report a line completes, if it completes one. */
    fun parseLine(line: String): MemoryUsage? {
        val text = line.trim()
        HEADER.matchEntire(text)?.let { match ->
            current = Builder(match.groupValues[1].lowercase(), match.groupValues[2].lowercase().ifEmpty { "app" })
            return null
        }
        val report = current ?: return null
        RESOURCES.matchEntire(text)?.let {
            report.resources = it.groupValues[1].toLong()
            report.resourceLimit = kilobytes(it.groupValues[2])
            return null
        }
        RAM.matchEntire(text)?.let {
            report.ram = it.groupValues[1].toLong()
            report.ramLimit = kilobytes(it.groupValues[2])
            return null
        }
        HEAP.matchEntire(text)?.let {
            report.heapFree = it.groupValues[1].toLong()
            return null
        }
        if (RULE.matches(text) && report.ram != null) {
            current = null
            return report.build()
        }
        return null
    }

    private class Builder(val platform: String, val binary: String) {
        var ram: Long? = null
        var ramLimit: Long? = null
        var heapFree: Long? = null
        var resources: Long? = null
        var resourceLimit: Long? = null

        fun build() = MemoryUsage(platform, binary, ram!!, ramLimit, heapFree, resources, resourceLimit)
    }

    private fun kilobytes(text: String): Long? = text.toDoubleOrNull()?.let { (it * 1024).toLong() }

    private companion object {
        val HEADER = Regex("""([A-Z]+)(?: (APP|WORKER|LIB))? MEMORY USAGE""")
        val RESOURCES = Regex("""Total size of resources:\s+(\d+) bytes(?: / ([\d.]+)KB)?""")
        val RAM = Regex("""Total footprint in RAM:\s+(\d+) bytes(?: / ([\d.]+)KB)?""")
        val HEAP = Regex("""Free RAM available \(heap\):\s+(-?\d+) bytes""")
        val RULE = Regex("""-{10,}""")
    }
}

/**
 * The firmware's account of an app's heap as it exits:
 * `Heap Usage for App <Fixture C>: Total Size <130848B> Used <24B> Still allocated <0B>`.
 * Anything still allocated is memory the app never freed.
 */
data class HeapUsage(val app: String, val total: Long, val used: Long, val stillAllocated: Long) {
    companion object {
        private val LINE = Regex("""Heap Usage for App <(.+?)>: Total Size <(\d+)B> Used <(\d+)B> Still allocated <(\d+)B>""")

        fun parse(line: String): HeapUsage? = LINE.find(line)?.let {
            val (app, total, used, still) = it.destructured
            HeapUsage(app, total.toLong(), used.toLong(), still.toLong())
        }
    }
}
