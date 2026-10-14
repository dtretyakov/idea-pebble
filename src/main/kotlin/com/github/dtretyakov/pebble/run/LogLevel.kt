package com.github.dtretyakov.pebble.run

/**
 * A watch log line's level, which pebble-tool prints only as a colour — the escape sequences its
 * `PebbleLogPrinter.colour_scheme` writes before the line, captured from pebble-tool 5.0.40:
 *
 * ```
 * \e[41m\e[1m\e[37m[06:03:16] main.c:3> an error\e[0m
 * \e[1m\e[31m[06:03:16] main.c:4> a warning\e[0m
 * [06:03:16] main.c:5> an info
 * \e[35m[06:03:16] main.c:6> a debug\e[0m
 * \e[36m[06:03:16] main.c:7> a verbose\e[0m
 * ```
 *
 * PebbleKit JS lines are uncoloured, and so read as info.
 */
enum class LogLevel(val display: String) {
    VERBOSE("Verbose"),
    DEBUG("Debug"),
    INFO("Info"),
    WARNING("Warning"),
    ERROR("Error");

    override fun toString(): String = display

    companion object {
        fun of(name: String?): LogLevel = entries.firstOrNull { it.name == name } ?: VERBOSE

        /** The level of a log line, or null when the line is not a log line at all. */
        fun ofLine(line: String): LogLevel? {
            val match = LOG_LINE.find(line) ?: return null
            val colours = match.groupValues[1]
            return when {
                "\u001b[41m" in colours -> ERROR
                "\u001b[31m" in colours -> WARNING
                "\u001b[35m" in colours -> DEBUG
                "\u001b[36m" in colours -> VERBOSE
                else -> INFO
            }
        }

        /** Whether [line] is shown when only [minimum] and above are wanted: anything not a log line is. */
        fun passes(line: String, minimum: LogLevel): Boolean =
            minimum == VERBOSE || (ofLine(line)?.let { it >= minimum } ?: true)

        private val LOG_LINE = Regex("""^((?:\u001b\[[0-9;]*m)*)\[\d{2}:\d{2}:\d{2}] """)
    }
}
