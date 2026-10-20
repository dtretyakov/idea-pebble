package com.github.dtretyakov.pebble.project

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFile
import com.intellij.openapi.vfs.newvfs.NewVirtualFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import java.nio.file.Files
import java.nio.file.Path
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
        // A project with no module, as the New Project wizard makes one, is its base directory.
        val candidates = contentRoots.ifEmpty { listOfNotNull(project.guessProjectDir()?.toNioPathOrNull() ?: project.basePath?.let(Path::of)) }
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
     * Asked on every editor that opens, on the UI thread as often as not, and walking up a tree
     * asks it once per directory — so it is answered from the IDE's virtual file system, which
     * holds what it has seen in memory, rather than from the disk. For a project in WSL the disk is
     * a request to the IDE's agent in the distribution, and one that stalls held the UI thread with
     * it. A directory the IDE has not seen yet is read from the disk only off the UI thread.
     */
    fun infoAt(root: Path): PebbleProjectInfo? {
        val file = when (val found = findFile(root, PebbleProjectInfo.FILE_NAME)) {
            Found.Unknown -> {
                if (!isUiThread()) return fromDisk(root)
                // What the disk said stands until it can be asked again off the UI thread. What the
                // virtual file system said does not: it forgets no file it has seen but a deleted one.
                val (stamp, info) = cache[root] ?: return null
                if (stamp.first == FROM_DISK) return info
                null
            }
            Found.Absent -> null
            is Found.File -> found.file
        }
        if (file == null || file.isDirectory) {
            cache.remove(root)
            return null
        }
        val stamp = file.modificationStamp to file.timeStamp
        cache[root]?.let { (cachedStamp, info) -> if (cachedStamp == stamp) return info }
        val info = runCatching { PebbleProjectInfo.parse(VfsUtilCore.loadText(file)) }.getOrNull()
        cache[root] = stamp to info
        return info
    }

    /** What the virtual file system says of a file. */
    internal sealed interface Found {
        /** Not known to it, or on the UI thread not yet seen: only the disk can tell. */
        data object Unknown : Found
        data object Absent : Found
        data class File(val file: VirtualFile) : Found
    }

    /**
     * [name] in [directory], from the virtual file system. On the UI thread, only from what it
     * already holds — a path it has not seen would be looked up on the disk segment by segment,
     * and a directory it has not listed would be listed — so there an unseen file is [Found.Unknown].
     */
    internal fun findFile(directory: Path, name: String): Found {
        val onUiThread = isUiThread()
        val files = LocalFileSystem.getInstance()
        val folder = if (onUiThread && files is NewVirtualFileSystem) {
            files.findFileByPathIfCached(FileUtil.toSystemIndependentName(directory.toString()))
        } else {
            files.findFileByNioFile(directory)
        } ?: return Found.Unknown
        if (!folder.isValid || !folder.isDirectory) return Found.Absent
        if (onUiThread && folder is NewVirtualFile) {
            return folder.findChildIfCached(name)?.takeIf { it.isValid }?.let(Found::File) ?: Found.Unknown
        }
        return folder.findChild(name)?.takeIf { it.isValid }?.let(Found::File) ?: Found.Absent
    }

    internal fun isUiThread(): Boolean = ApplicationManager.getApplication()?.isDispatchThread == true

    /** As [infoAt], from the disk: for a directory the virtual file system does not know yet. */
    private fun fromDisk(root: Path): PebbleProjectInfo? {
        val file = root.resolve(PebbleProjectInfo.FILE_NAME)
        val modified = runCatching { Files.getLastModifiedTime(file) }.getOrNull() ?: return null.also { cache.remove(root) }
        val stamp = FROM_DISK to modified.toMillis()
        cache[root]?.let { (cachedStamp, info) -> if (cachedStamp == stamp) return info }
        val info = runCatching { PebbleProjectInfo.parse(file.readText()) }.getOrNull()
        cache[root] = stamp to info
        return info
    }

    private val cache = ConcurrentHashMap<Path, Pair<Pair<Long, Long>, PebbleProjectInfo?>>()

    companion object {
        /** In place of a modification stamp, for what was read from the disk rather than through the virtual file system. */
        private const val FROM_DISK = -1L

        fun getInstance(project: Project): PebbleProject = project.service()
    }
}
