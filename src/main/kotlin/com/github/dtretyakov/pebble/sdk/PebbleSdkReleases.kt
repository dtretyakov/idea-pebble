package com.github.dtretyakov.pebble.sdk

import com.intellij.util.io.HttpRequests
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a developer weighs about an SDK version beyond its number: whether it is the newest, and
 * what changed in it.
 *
 * The newest is pebble-tool's to say — the versions `pebble sdk list` offers, with those already
 * installed. What changed is on the download pebble-tool installs from: its description of a
 * version, `https://sdk.repebble.com/v1/files/sdk-core/<version>`, links to the release notes in a
 * sentence — `"release_notes": "Check out the release notes at https://…/4.17/index.html"`.
 */
object PebbleSdkReleases {

    /** The newest version there is: installed or offered. */
    fun latest(installed: List<String>, available: List<String>): String? =
        (installed + available).maxByOrNull(SdkVersion::of)

    /** The release notes' address in the download server's description of a version. */
    fun notesUrl(description: String): String? {
        val json = runCatching { Json.parseToJsonElement(description) as? JsonObject }.getOrNull() ?: return null
        val notes = json["release_notes"]?.jsonPrimitive?.contentOrNull ?: return null
        return URL.find(notes)?.value?.trimEnd('.', ',', ')')
    }

    /** The release notes of [version], asked of the download server; null when it cannot say. Blocks. */
    fun fetchNotesUrl(version: String): String? = runCatching {
        notesUrl(HttpRequests.request("$SERVER/v1/files/sdk-core/$version?channel=").productNameAsUserAgent().readString())
    }.getOrNull()

    private const val SERVER = "https://sdk.repebble.com"
    private val URL = Regex("""https?://\S+""")
}
