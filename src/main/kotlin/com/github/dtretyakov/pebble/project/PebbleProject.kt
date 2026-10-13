package com.github.dtretyakov.pebble.project

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.readText

/**
 * The Pebble projects inside an IDE project: every directory whose `package.json` has a `pebble`
 * section — the same test pebble-tool applies before it will build anything.
 *
 * There is no facet and no module type behind this. A Pebble project is whatever pebble-tool
 * would build, so opening the folder is the whole of importing one, in every IDE alike.
 */
@Service(Service.Level.PROJECT)
class PebbleProject(private val project: Project) {

    /** Every Pebble project root in this IDE project, outermost first. */
    fun roots(): List<Path> {
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots.mapNotNull { it.toNioPathOrNull() }
        val candidates = contentRoots.ifEmpty { listOfNotNull(project.guessProjectDir()?.toNioPathOrNull()) }
        return candidates.filter { infoAt(it) != null }.distinct()
    }

    /** The Pebble project a file belongs to, found by walking up from it; null outside one. */
    fun rootFor(file: VirtualFile): Path? {
        val start = file.toNioPathOrNull() ?: return null
        val stop = project.guessProjectDir()?.toNioPathOrNull()?.parent
        var current: Path? = if (file.isDirectory) start else start.parent
        while (current != null && current != stop) {
            if (infoAt(current) != null) return current
            current = current.parent
        }
        return null
    }

    fun isPebbleProject(): Boolean = roots().isNotEmpty()

    /** The single project when there is one, so commands that need "the project" have an answer. */
    fun primaryRoot(): Path? = roots().firstOrNull()

    /** The platforms the project at [root] can be built for, in its own order. */
    fun platforms(root: Path): List<String> = ActivePlatform.candidates(
        infoAt(root)?.targetPlatforms.orEmpty(),
        PebbleSdkService.getInstance().currentSdk()?.platforms().orEmpty(),
    )

    /** The platform Build, Run and code insight use for the project at [root]. */
    fun activePlatform(root: Path): String? =
        ActivePlatform.resolve(PebbleSettings.getInstance(project).activePlatform, platforms(root))

    /** Makes [platform] the active one, and tells everything that reads it. */
    fun setActivePlatform(platform: String) {
        val settings = PebbleSettings.getInstance(project)
        if (settings.activePlatform == platform) return
        settings.activePlatform = platform
        project.messageBus.syncPublisher(PebbleSettings.TOPIC).activePlatformChanged(project)
    }

    /**
     * The project at [root], re-read only when its `package.json` has changed.
     *
     * Asked on every editor that opens, and walking up a tree asks it once per directory, so it is
     * a stat in the common case rather than a read and a parse.
     */
    fun infoAt(root: Path): PebbleProjectInfo? {
        val file = root.resolve(PebbleProjectInfo.FILE_NAME)
        val stamp = runCatching { Files.getLastModifiedTime(file) }.getOrNull()
        if (stamp == null) {
            cache.remove(root)
            return null
        }
        cache[root]?.let { (cachedStamp, info) -> if (cachedStamp == stamp) return info }
        val info = runCatching { PebbleProjectInfo.parse(file.readText()) }.getOrNull()
        cache[root] = stamp to info
        return info
    }

    private val cache = ConcurrentHashMap<Path, Pair<FileTime, PebbleProjectInfo?>>()

    companion object {
        fun getInstance(project: Project): PebbleProject = project.service()
    }
}
