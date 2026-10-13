package com.github.dtretyakov.pebble.sdk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * One installed SDK: `<data dir>/SDKs/<version>/`, as `pebble sdk install` lays it out.
 *
 * ```
 * SDKs/4.33.1/sdk-core/manifest.json          { "version": "4.33.1", "channel": "release", … }
 * SDKs/4.33.1/sdk-core/pebble/<platform>/include/pebble.h
 * SDKs/4.33.1/toolchain/arm-none-eabi/bin/    gcc, gdb
 * SDKs/4.33.1/toolchain/bin/                  qemu-pebble
 * SDKs/4.33.1/toolchain/moddable-tools/       mcrun, xsbug — Alloy only
 * SDKs/current -> SDKs/4.33.1                 the active one
 * ```
 */
class PebbleSdk(val root: Path, val version: String, val channel: String?) {

    val core: Path get() = root.resolve("sdk-core")

    val toolchain: Path get() = root.resolve("toolchain")

    /** `arm-none-eabi-gcc` and `-gdb`: without them nothing in C builds. */
    val compilerBin: Path get() = toolchain.resolve("arm-none-eabi/bin")

    val hasCompiler: Boolean get() = compilerBin.isDirectory()

    /** `mcrun` and `xsbug`, which only SDKs with Alloy support carry. */
    val hasAlloyTools: Boolean get() = Files.exists(toolchain.resolve("moddable-tools/mcrun"))

    /** Where `pebble.h` lives for one platform — what `pebble sdk include-path` prints. */
    fun includeDir(platform: String): Path = core.resolve("pebble").resolve(platform).resolve("include")

    /** The platforms this SDK can build for: those it ships a `pebble.h` for. */
    fun platforms(): List<String> {
        val pebble = core.resolve("pebble")
        if (!pebble.isDirectory()) return emptyList()
        return pebble.listDirectoryEntries()
            .filter { Files.exists(it.resolve("include/pebble.h")) }
            .map { it.name }
            .sortedBy { PebblePlatform.of(it)?.ordinal ?: Int.MAX_VALUE }
    }

    override fun toString(): String = "Pebble SDK $version"

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The SDK at [root], or null when that is not a complete installation. */
        fun at(root: Path): PebbleSdk? {
            val manifest = root.resolve("sdk-core/manifest.json")
            if (!Files.isRegularFile(manifest)) return null
            val fields = runCatching { json.parseToJsonElement(manifest.readText()) as? JsonObject }.getOrNull()
                ?: return null
            val version = fields["version"]?.jsonPrimitive?.contentOrNull ?: return null
            return PebbleSdk(root, version, fields["channel"]?.jsonPrimitive?.contentOrNull)
        }
    }
}

/** Every SDK under one data directory, and which of them is active. */
class PebbleSdks(val installed: List<PebbleSdk>, val current: PebbleSdk?) {

    companion object {
        val NONE = PebbleSdks(emptyList(), null)

        /**
         * Read straight off disk rather than from `pebble sdk list`, whose output is prose for a
         * person and costs a Python start-up to get. This is the same walk pebble-tool's
         * `list_local_sdks` makes: every directory with a readable `sdk-core/manifest.json`, the
         * `current` link excepted.
         */
        fun scan(dataDir: Path?): PebbleSdks {
            val sdks = dataDir?.resolve("SDKs")?.takeIf { it.isDirectory() } ?: return NONE
            val installed = sdks.listDirectoryEntries()
                .filter { !Files.isSymbolicLink(it) && it.isDirectory() }
                .mapNotNull { PebbleSdk.at(it) }
                .sortedByDescending { SdkVersion.of(it.version) }

            val current = sdks.resolve("current").takeIf { Files.exists(it) }?.let { link ->
                val target = runCatching { link.toRealPath() }.getOrNull()
                installed.firstOrNull { runCatching { it.root.toRealPath() }.getOrNull() == target }
                    ?: PebbleSdk.at(link)
            }
            return PebbleSdks(installed, current)
        }
    }
}

/** An SDK version, ordered the way pebble-tool orders them: numerically, part by part. */
data class SdkVersion(private val parts: List<Int>) : Comparable<SdkVersion> {

    override fun compareTo(other: SdkVersion): Int {
        for (i in 0 until maxOf(parts.size, other.parts.size)) {
            val difference = parts.getOrElse(i) { 0 } - other.parts.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }

    companion object {
        /** Lenient: `4.33.1`, `4.9-beta3` and `4.x` all order rather than fail. */
        fun of(text: String): SdkVersion =
            SdkVersion(text.split('.', '-').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 })
    }
}
