package com.github.dtretyakov.pebble.project

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ContentEntry
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.jps.model.java.JavaResourceRootType
import org.jetbrains.jps.model.java.JavaSourceRootType
import org.jetbrains.jps.model.module.JpsModuleSourceRootType

/**
 * Tells the IDE how a Pebble project is laid out, the first time it opens.
 *
 * There is no build file for the IDE to import — waf reads `package.json` and a fixed layout — so
 * a project opened as a folder was just a folder: `src/` and `worker_src/` plain directories, and
 * `build/`, where waf puts everything it generates, indexed and searched like the code, so Go to
 * File offered the build's copies beside the files the user wrote. clangd's index and waf's lock
 * file are left out of the tree for the same reason.
 */
class PebbleProjectSetup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val settings = PebbleSettings.getInstance(project)
        if (settings.rootsConfigured) return

        val work = readAction {
            val index = ProjectFileIndex.getInstance(project)
            PebbleProject.getInstance(project).roots().mapNotNull { root ->
                val directory = LocalFileSystem.getInstance().findFileByNioFile(root) ?: return@mapNotNull null
                index.getModuleForFile(directory)?.let { module -> module to directory }
            }
        }
        if (work.isEmpty()) return

        edtWriteAction {
            if (project.isDisposed) return@edtWriteAction
            work.forEach { (module, directory) -> configure(module, directory) }
            settings.rootsConfigured = true
        }
    }

    private fun configure(module: Module, root: VirtualFile) {
        // Only an untouched module: one with source roots has been arranged by someone, and their
        // arrangement is not ours to second-guess.
        if (ModuleRootManager.getInstance(module).sourceRoots.isNotEmpty()) return

        ModuleRootModificationUtil.updateModel(module) { model ->
            val entry = model.contentEntries.firstOrNull { it.file == root } ?: return@updateModel
            SOURCES.forEach { mark(entry, root, it, JavaSourceRootType.SOURCE) }
            mark(entry, root, RESOURCES, JavaResourceRootType.RESOURCE)
            // By URL, so they hold before the first build and the first clangd have made them.
            EXCLUDED.map { "${root.url}/$it" }
                .filter { it !in entry.excludeFolderUrls }
                .forEach(entry::addExcludeFolder)
            // waf's lock file is a file beside the sources, so a pattern rather than a folder.
            if (WAF_LOCK !in entry.excludePatterns) entry.addExcludePattern(WAF_LOCK)
        }
    }

    /**
     * The root types are the Java ones, and that is not a mistake: they live in the platform's
     * `jps-model`, in every IDE, and they are the vocabulary an `.iml` is read with. A Pebble
     * source folder is a source folder.
     */
    private fun mark(entry: ContentEntry, root: VirtualFile, name: String, type: JpsModuleSourceRootType<*>) {
        val directory = root.findChild(name)?.takeIf { it.isDirectory } ?: return
        if (entry.sourceFolders.any { it.file == directory }) return
        runCatching { entry.addSourceFolder(directory, type) }
            .onFailure { LOG.warn("Could not mark $name as a $type root", it) }
    }

    private companion object {
        /** C, PebbleKit JS and Alloy all live under `src/`; a background worker under `worker_src/`. */
        val SOURCES = listOf("src", "worker_src")
        const val RESOURCES = "resources"

        /** waf's output, and clangd's index (`--background-index` keeps it in `.cache/clangd`). */
        val EXCLUDED = listOf("build", ".cache")
        const val WAF_LOCK = ".lock-waf*"

        val LOG = logger<PebbleProjectSetup>()
    }
}
