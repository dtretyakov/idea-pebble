package com.github.dtretyakov.pebble.emulator

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * The All Platforms tab's pictures: the app on each platform it targets, in `build/preview/`,
 * one of each — build output, not the project's.
 *
 * `pebble screenshot --all-platforms` has no say in where it writes: always the project's
 * `screenshots/`, which is the user's — a hand-picked set to hand to `pebble publish --screenshots`.
 * So a capture's files are moved out of it as soon as it is done, by name: the ones that were not
 * there before it started.
 */
object PlatformPreview {

    const val DIRECTORY = "build/preview"

    fun directory(root: Path): Path = root.resolve(DIRECTORY)

    /** The newest preview of each platform, in the order of [platforms]. */
    fun latest(root: Path, platforms: List<String>): List<PlatformScreenshots.Shot> = PlatformScreenshots.latest(directory(root), platforms)

    /** What `screenshots/` holds before a capture: null when there is no such folder. */
    fun before(root: Path): Set<String>? {
        val screenshots = root.resolve(PlatformScreenshots.DIRECTORY)
        return if (screenshots.isDirectory()) screenshots.listDirectoryEntries().map { it.name }.toSet() else null
    }

    /**
     * The screenshots a capture added to `screenshots/`, moved to the preview, each in place of the
     * earlier preview of its platform; and `screenshots/` removed again when the capture made it
     * and left it empty. The moved files.
     */
    fun collect(root: Path, before: Set<String>?): List<Path> {
        val screenshots = root.resolve(PlatformScreenshots.DIRECTORY)
        if (!screenshots.isDirectory()) return emptyList()
        val added = screenshots.listDirectoryEntries()
            .filter { it.name !in before.orEmpty() && PlatformScreenshots.parse(it) != null }
        val preview = directory(root).createDirectories()
        val moved = added.map { file ->
            Files.move(file, preview.resolve(file.name), StandardCopyOption.REPLACE_EXISTING).also(::prune)
        }
        if (before == null && screenshots.listDirectoryEntries().isEmpty()) screenshots.deleteIfExists()
        return moved
    }

    /** The earlier previews of [kept]'s platform, deleted: the tab shows one of each. */
    fun prune(kept: Path) {
        val platform = PlatformScreenshots.parse(kept)?.platform ?: return
        kept.parent.listDirectoryEntries()
            .filter { it != kept && PlatformScreenshots.parse(it)?.platform == platform }
            .forEach { it.deleteIfExists() }
    }
}
