package com.github.dtretyakov.pebble.build

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.name

/**
 * What the Pebble appstore asks of a release that can be checked before submitting it, from its
 * guide ("Preparing a Submission", developer.repebble.com, October 2026):
 *
 * - `version` of numbers and dots only, each part 255 or less, and new with each release;
 * - a `uuid` that stays the same from release to release, and is the app's own;
 * - a `.pbw` of at most 4.4 MB, with a binary for each platform it targets.
 *
 * And what the build itself reports: resources over the limit the store enforces. Whether the
 * version is new, the description, category and banner are the store's to see. Screenshots are
 * `pebble publish`'s: it captures them, or takes the files it is given, and uploads them.
 */
object ReleaseCheck {

    @Serializable
    data class Item(
        /** `error`: the store would refuse it; `warning`: it would take it, worse off; `ok`. */
        val severity: String,
        val check: String,
        val message: String,
        val file: String? = null,
    )

    /** "about 4.4 MB", read as the smaller of its two meanings. */
    const val MAX_BUNDLE_BYTES = 4_400_000L

    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")

    fun packageJson(text: String): List<Item> {
        val json = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return listOf(Item("error", "package.json", "package.json is not JSON."))
        val pebble = json["pebble"] as? JsonObject ?: return listOf(Item("error", "package.json", "package.json has no pebble section."))
        val items = mutableListOf<Item>()
        val version = json["version"]?.jsonPrimitive?.contentOrNull
        items += when {
            version == null -> Item("error", "version", "package.json has no version.")
            !version.matches(Regex("""\d+(\.\d+)*""")) -> Item("error", "version", "The store takes a version of numbers and dots only; '$version' is not one.")
            version.split('.').any { it.toInt() > 255 } -> Item("error", "version", "Each part of the version must be 255 or less; '$version' has a larger one.")
            else -> Item("ok", "version", "Version $version. The store refuses one it has had before: raise it for each release.")
        }
        val uuid = pebble["uuid"]?.jsonPrimitive?.contentOrNull
        items += when {
            uuid == null -> Item("error", "uuid", "package.json has no pebble.uuid.")
            !UUID.matches(uuid) -> Item("error", "uuid", "'$uuid' is not a UUID.")
            else -> Item("ok", "uuid", "UUID $uuid. Keep it for every release; a new one is a new app in the store.")
        }
        return items
    }

    fun bundle(pbw: Path?, targets: List<String>): List<Item> {
        if (pbw == null || !Files.isRegularFile(pbw)) return listOf(Item("error", "bundle", "No .pbw was built."))
        val items = mutableListOf<Item>()
        val size = Files.size(pbw)
        items += if (size > MAX_BUNDLE_BYTES) {
            Item("error", "bundle size", "The .pbw is ${size / 1000} KB; the store takes at most 4.4 MB.", pbw.toString())
        } else {
            Item("ok", "bundle size", "The .pbw is ${size / 1000} KB of the store's 4.4 MB.", pbw.toString())
        }
        val entries = runCatching { ZipFile(pbw.toFile()).use { zip -> zip.entries().asSequence().map { it.name }.toSet() } }
            .getOrElse { return items + Item("error", "bundle", "The .pbw cannot be read as a zip: ${it.message}", pbw.toString()) }
        val missing = targets.filter { "$it/pebble-app.bin" !in entries }
        items += if (missing.isEmpty()) {
            Item("ok", "platforms", "A binary for each target platform: ${targets.joinToString()}.")
        } else {
            Item("error", "platforms", "No binary in the .pbw for ${missing.joinToString()}, which package.json targets.", pbw.toString())
        }
        return items
    }

    fun memory(usages: List<MemoryUsage>): List<Item> = usages.mapNotNull { usage ->
        when {
            usage.resources != null && usage.resourceLimit != null && usage.resources > usage.resourceLimit ->
                Item("error", "resources", "${usage.platform}: ${usage.resources} bytes of resources, over the store's ${usage.resourceLimit}.")
            usage.isTight -> Item("warning", "memory", "${usage.platform}: little heap left (${usage.heapFree} bytes free of ${usage.ramLimit}).")
            else -> null
        }
    }

}
