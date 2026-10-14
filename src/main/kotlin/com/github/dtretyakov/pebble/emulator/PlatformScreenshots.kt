package com.github.dtretyakov.pebble.emulator

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The screenshots `pebble screenshot --all-platforms` leaves in the project's `screenshots/`: one
 * per platform per run, named `<platform>_<version>_<yyyyMMdd-HHmmss>.png`, with the watch's clock
 * at 10:10 — the store's convention.
 */
object PlatformScreenshots {

    data class Shot(val platform: String, val version: String, val taken: String, val file: Path)

    fun parse(file: Path): Shot? {
        val match = NAME.matchEntire(file.name) ?: return null
        val (platform, version, taken) = match.destructured
        return Shot(platform, version, taken, file)
    }

    /** The newest screenshot of each platform, in the order of [platforms] where one is given. */
    fun latest(directory: Path, platforms: List<String> = emptyList()): List<Shot> {
        if (!directory.isDirectory()) return emptyList()
        val newest = directory.listDirectoryEntries()
            .filter(Files::isRegularFile)
            .mapNotNull(::parse)
            .groupBy { it.platform }
            .mapValues { (_, shots) -> shots.maxBy { it.taken } }
        val order = platforms + newest.keys.sorted().filterNot { it in platforms }
        return order.mapNotNull(newest::get)
    }

    const val DIRECTORY = "screenshots"

    private val NAME = Regex("""([a-z]+)_(.+)_(\d{8}-\d{6})\.png""")
}
