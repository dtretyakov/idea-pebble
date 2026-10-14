package com.github.dtretyakov.pebble.sdk

/**
 * The SDK versions available to install, read from `pebble sdk list`.
 *
 * The one thing about SDKs only pebble-tool can say: which versions its download server offers.
 * What is installed is read from disk instead ([PebbleSdks]). The output is prose:
 *
 * ```
 * Installed SDKs:
 * 4.33.1 (active)
 *
 * Available SDKs:
 * 4.4
 * 4.9.148 (beta)
 * ```
 *
 * The heading gains a channel — `Available SDKs (beta channel):` — when one is set, and the list
 * leaves out what is already installed.
 */
object PebbleSdkList {

    fun available(output: String): List<String> {
        val lines = output.lines()
        val start = lines.indexOfFirst { it.startsWith("Available SDKs") && it.trimEnd().endsWith(":") }
        if (start < 0) return emptyList()
        return lines.drop(start + 1)
            .map { it.trim() }
            .takeWhile { it.isNotEmpty() && !it.startsWith("Could not fetch") }
            .map { it.substringBefore(" ") }
            .filter { VERSION.matches(it) }
            .sortedByDescending(SdkVersion::of)
    }

    private val VERSION = Regex("""\d+(\.\d+)*(-\S+)?""")
}
