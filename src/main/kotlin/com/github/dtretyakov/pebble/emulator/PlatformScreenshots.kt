package com.github.dtretyakov.pebble.emulator

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
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

    /**
     * pebble-tool's names, which the Screen tab's Save Screenshot now writes too; and that save's
     * former `<platform>-<yyyyMMdd-HHmmss>.png`, so a screenshot taken before still counts.
     */
    fun parse(file: Path): Shot? {
        NAME.matchEntire(file.name)?.destructured?.let { (platform, version, taken) -> return Shot(platform, version, taken, file) }
        FORMER.matchEntire(file.name)?.destructured?.let { (platform, taken) -> return Shot(platform, "", taken, file) }
        return null
    }

    /** The name pebble-tool gives a screenshot of [platform] in an app at [appVersion], taken [at]. */
    fun fileName(platform: String, appVersion: String?, at: LocalDateTime = LocalDateTime.now()): String {
        val version = appVersion?.takeIf { it.isNotBlank() }?.replace(UNSAFE, "_") ?: "unknown"
        return "${platform}_${version}_${at.format(STAMP)}.png"
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
    private val FORMER = Regex("""([a-z]+)-(\d{8}-\d{6})\.png""")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val UNSAFE = Regex("""[^A-Za-z0-9.\-]""")
}
