package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.PebbleSdk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The typings that give an Alloy app its globals — `watch`, `console`, `Resource`, `trace` — as
 * the build checks the app against them.
 *
 * They are no module's: the build names them in `compilerOptions.types` of its
 * `tsconfig-js.json`, by absolute path and without an extension, and nothing imports them. The
 * IDE's JavaScript takes a global name from neither a `tsconfig.json` nor an ordinary library, so
 * `watch` stayed unresolved while every import resolved; these files are given to it as a library
 * of its own (`PebbleJsLibraries`).
 */
object AlloyGlobals {

    /**
     * The `.d.ts` files of the last build's `types`, for [platform]; before a first build, those
     * the SDK's build has always named, from [sdk].
     */
    fun files(root: Path, platform: String?, sdk: PebbleSdk?): List<Path> {
        val generated = platform?.let { TypeScriptConfig.generated(root, it) }
        val types = generated?.let { runCatching { typesIn(it.readText()) }.getOrNull() }
            ?: sdk?.let { DEFAULT.map { name -> sdk.toolchain.resolve("moddable").resolve(name).toString() } }
            ?: return emptyList()
        return types.map { Path.of("$it.d.ts") }.filter { it.isRegularFile() }
    }

    /** `compilerOptions.types` of a `tsconfig.json`'s text. */
    fun typesIn(tsconfig: String): List<String>? {
        val options = (Json.parseToJsonElement(tsconfig) as? JsonObject)?.get("compilerOptions") as? JsonObject ?: return null
        return (options["types"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
    }

    /** As SDK 4.33.1's build names them, relative to `toolchain/moddable`. */
    private val DEFAULT = listOf(
        "typings/pebble/global", "typings/pebble/piu", "typings/pebble/poco", "typings/easing",
        "typings/piu/MC", "typings/piu/MC-types", "xs/includes/xs", "typings/global",
    )
}
