package com.github.dtretyakov.pebble.sdk

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText

/**
 * The SDK functions a platform only pretends to have.
 *
 * Every platform's `pebble.h` declares the whole API. What a platform lacks — the speaker, touch,
 * the RGB backlight, heart rate variability on aplite, basalt, chalk and diorite — is there as a
 * macro that swallows its arguments: `#define speaker_play_notes(...) (0)`. A call compiles on
 * every platform, `-Werror` and all, and on those platforms does nothing. Nothing in the build
 * says so; the header is the only place it is written down.
 */
object PlatformStubs {

    /** The stubbed functions in one `pebble.h`, each with what it expands to. */
    fun parse(header: String): Map<String, String> =
        STUB.findAll(header).associate { it.groupValues[1] to it.groupValues[2].trim() }

    /**
     * Whether a stub stands for "nothing happens here": `(0)`, `(false)`, `(NULL)` or an empty
     * statement. A stub with any other value — `persist_get_max_size` as `(4096)` on aplite — is
     * the SDK's answer for that platform rather than a missing feature, and is no mistake to call.
     */
    fun isInert(expansion: String): Boolean = expansion.replace(" ", "") in INERT

    /** The inert stubs of [platform] in [sdk], read again only when its `pebble.h` changes. */
    fun inert(sdk: PebbleSdk, platform: String): Set<String> {
        val header = sdk.includeDir(platform).resolve("pebble.h")
        val stamp = runCatching { Files.getLastModifiedTime(header) }.getOrNull() ?: return emptySet()
        cache[header]?.let { (cachedStamp, names) -> if (cachedStamp == stamp) return names }
        val names = runCatching { parse(header.readText()) }.getOrDefault(emptyMap())
            .filterValues(::isInert).keys
        cache[header] = stamp to names
        return names
    }

    private val cache = ConcurrentHashMap<Path, Pair<FileTime, Set<String>>>()

    private val STUB = Regex("""^#define ([a-z_][a-z0-9_]*)\(\.\.\.\)[ \t]+(.+)$""", RegexOption.MULTILINE)

    private val INERT = setOf("(0)", "(false)", "(NULL)", "do{}while(0)")
}
