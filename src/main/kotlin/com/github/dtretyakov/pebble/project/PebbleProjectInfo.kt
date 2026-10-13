package com.github.dtretyakov.pebble.project

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * What a Pebble project says about itself in the `pebble` section of its `package.json`.
 *
 * Only what the plugin acts on, read leniently: pebble-tool is the judge of whether a project is
 * valid, and it says so with a message of its own the moment the project is built. The plugin
 * refusing to recognise a project pebble-tool would have built — or explained the problem less
 * well than pebble-tool does — would be worse than recognising one it will reject.
 */
data class PebbleProjectInfo(
    val name: String?,
    val displayName: String?,
    val uuid: String?,
    val projectType: ProjectType,
    /** As written; empty means the field is absent, which pebble-tool reads as every platform. */
    val targetPlatforms: List<String>,
    val watchface: Boolean,
) {

    enum class ProjectType(val id: String) {
        /** C, optionally with PebbleKit JS on the phone. pebble-tool's default. */
        NATIVE("native"),

        /** Alloy: JavaScript or TypeScript running on the watch in Moddable XS. */
        ALLOY("moddable"),

        /** A library for other Pebble projects to depend on. */
        PACKAGE("package"),

        /** Rocky.js, which pebble-tool still builds and the SDK no longer advertises. */
        ROCKY("rocky"),

        UNKNOWN("");

        companion object {
            fun of(id: String?): ProjectType =
                if (id == null) NATIVE else entries.firstOrNull { it.id == id && it != UNKNOWN } ?: UNKNOWN
        }
    }

    companion object {
        const val FILE_NAME = "package.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The project described by this `package.json`, or null when it is not a Pebble project —
         * which is to say, as pebble-tool decides it: the file has no `pebble` key.
         */
        fun parse(packageJson: String): PebbleProjectInfo? {
            val root = runCatching { json.parseToJsonElement(packageJson) }.getOrNull() as? JsonObject ?: return null
            val pebble = root["pebble"] as? JsonObject ?: return null
            return PebbleProjectInfo(
                name = root["name"].string(),
                displayName = pebble["displayName"].string(),
                uuid = pebble["uuid"].string(),
                projectType = ProjectType.of(pebble["projectType"].string()),
                targetPlatforms = (pebble["targetPlatforms"] as? JsonArray).orEmpty().mapNotNull { it.string() },
                watchface = ((pebble["watchapp"] as? JsonObject)?.get("watchface") as? JsonPrimitive)?.booleanOrNull
                    ?: false,
            )
        }

        private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
