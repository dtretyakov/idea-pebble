package com.github.dtretyakov.pebble.project

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleTypeManager
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
 * file are left out of the tree for the same reason, and the JDK IDEA gives any folder it opens
 * is taken away again.
 */
class PebbleProjectSetup : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (PebbleSettings.getInstance(project).rootsConfigured) return
        val work = readAction { work(project) }
        if (work.isEmpty()) return
        edtWriteAction { if (!project.isDisposed) apply(project, work) }
    }

    companion object {
        /** [execute], on the UI thread, at once. */
        internal fun configureNow(project: Project) {
            val work = work(project)
            if (work.isNotEmpty()) WriteAction.run<RuntimeException> { apply(project, work) }
        }

        /** Each Pebble project, and the module it belongs to: none for one the wizard made. */
        private fun work(project: Project): List<Pair<Module?, VirtualFile>> {
            val index = ProjectFileIndex.getInstance(project)
            return PebbleProject.getInstance(project).roots().mapNotNull { root ->
                val directory = LocalFileSystem.getInstance().findFileByNioFile(root) ?: return@mapNotNull null
                index.getModuleForFile(directory) to directory
            }
        }

        /** Under a write action. Once: the wizard and the startup activity may both get here. */
        private fun apply(project: Project, work: List<Pair<Module?, VirtualFile>>) {
            if (PebbleSettings.getInstance(project).rootsConfigured) return
            val index = ProjectFileIndex.getInstance(project)
            work.forEach { (module, directory) ->
                val owner = module ?: index.getModuleForFile(directory) ?: newModule(project, directory) ?: return@forEach
                configure(owner, directory)
            }
            PebbleSettings.getInstance(project).rootsConfigured = true
        }

        /**
         * The module a folder gets when the IDE opens it, which the New Project wizard does not
         * make: a project it made — this version's, and every one made before — has none, and
         * nothing to lay out without one. Where the IDE keeps it, of the type it gives one.
         *
         * Only in a project with no module at all, and not in an IDE with its own C engine: CLion
         * keeps a project model of its own, which a module beside it would only confuse.
         */
        private fun newModule(project: Project, root: VirtualFile): Module? {
            if (ModuleManager.getInstance(project).modules.isNotEmpty() || IdeCapabilities.hasNativeC()) return null
            val file = root.toNioPath().resolve(Project.DIRECTORY_STORE_FOLDER).resolve("${root.name}.iml")
            val module = ModuleManager.getInstance(project).newModule(file, ModuleTypeManager.getInstance().defaultModuleType.id)
            ModuleRootModificationUtil.updateModel(module) { it.addContentEntry(root) }
            return module
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
                // No JDK. IDEA opens a folder as a Java module and gives it whichever JDK it finds, and
                // nothing in a Pebble project is Java: all it brought was the JDK under External
                // Libraries, to index, and the jQuery of the JDK's own javadoc pages, offered by
                // completion in the app's JavaScript.
                if (model.isSdkInherited) model.sdk = null
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

        /** C, PebbleKit JS and Alloy all live under `src/`; a background worker under `worker_src/`. */
        private val SOURCES = listOf("src", "worker_src")
        private const val RESOURCES = "resources"

        /** waf's output, and clangd's index (`--background-index` keeps it in `.cache/clangd`). */
        private val EXCLUDED = listOf("build", ".cache")
        private const val WAF_LOCK = ".lock-waf*"

        private val LOG = logger<PebbleProjectSetup>()
    }
}
