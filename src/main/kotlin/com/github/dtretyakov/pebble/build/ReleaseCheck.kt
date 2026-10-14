package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.emulator.PlatformScreenshots
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import javax.imageio.ImageIO
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * What the Pebble appstore asks of a release that can be checked before submitting it, from its
 * guide ("Preparing a Submission", developer.repebble.com, October 2026):
 *
 * - `version` of numbers and dots only, each part 255 or less, and new with each release;
 * - a `uuid` that stays the same from release to release, and is the app's own;
 * - a `.pbw` of at most 4.4 MB, with a binary for each platform it targets;
 * - screenshots exactly the platform's screen size — 144×168, 180×180, 200×228 or 260×260 —
 *   PNG, JPEG or GIF, up to five per platform, at least one in all.
 *
 * And what the build itself reports: resources over the limit the store enforces. Whether the
 * version is new, the description, category and banner are the store's to see.
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
    const val MAX_SCREENSHOTS = 5

    private val UUID = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val IMAGE = setOf("png", "jpg", "jpeg", "gif")

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

    /** The screenshots in [directory] — where Capture Every Platform puts them — by target platform. */
    fun screenshots(directory: Path, targets: List<String>): List<Item> {
        val files = if (directory.isDirectory()) directory.listDirectoryEntries().filter { it.extension.lowercase() in IMAGE } else emptyList()
        val byPlatform = files.groupBy { PlatformScreenshots.parse(it)?.platform ?: it.name.substringBefore('_') }
        val items = mutableListOf<Item>()
        for (target in targets) {
            val platform = PebblePlatform.of(target) ?: continue
            val shots = byPlatform[target].orEmpty()
            if (shots.isEmpty()) {
                items += Item("warning", "screenshots", "No screenshot of $target: the store shows the listing with none for it. Capture Every Platform takes them.")
                continue
            }
            val wrong = shots.filter { shot ->
                val image = runCatching { ImageIO.read(shot.toFile()) }.getOrNull()
                image == null || image.width != platform.width || image.height != platform.height
            }
            wrong.forEach { items += Item("error", "screenshots", "${it.name} is not $target's ${platform.width}×${platform.height}: the store takes the screen's own size, unframed.", it.toString()) }
            if (shots.size - wrong.size > MAX_SCREENSHOTS) {
                items += Item("warning", "screenshots", "${shots.size - wrong.size} screenshots of $target; the store takes $MAX_SCREENSHOTS — choose.")
            } else if (wrong.size < shots.size) {
                items += Item("ok", "screenshots", "${shots.size - wrong.size} screenshot(s) of $target at ${platform.width}×${platform.height}.")
            }
        }
        if (files.isEmpty()) items += Item("error", "screenshots", "No screenshots at all: the store needs at least one.")
        return items
    }
}
