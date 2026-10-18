package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.build.BuildResult
import com.github.dtretyakov.pebble.build.PebbleBuild
import com.github.dtretyakov.pebble.library.PebbleLibraryProvider
import com.github.dtretyakov.pebble.project.AlloyGlobals
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.lang.javascript.library.JSLibraryManager
import com.intellij.lang.javascript.library.JSPredefinedLibraryProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.webcore.libraries.ScriptingLibraryModel
import org.jetbrains.annotations.TestOnly
import java.nio.file.Path
import java.util.Collections
import java.util.WeakHashMap

/**
 * A Pebble project's JavaScript globals, for the IDE's JavaScript: predefined libraries, the
 * JavaScript plugin's own way of shipping typings — the way it ships those in `node_modules`.
 *
 * - PebbleKit JS's `Pebble` object, from the plugin's own typings, for the phone half of an app.
 * - An Alloy app's globals — `watch`, `console`, `Resource` — from the typings the build names in
 *   its TypeScript settings ([AlloyGlobals]).
 *
 * An ordinary library under External Libraries is indexed and browsable, but not where JavaScript
 * resolves a global name from, and neither is a `tsconfig.json`: `Pebble` and `watch` were
 * unresolved while every import resolved. Like `node_modules`' libraries these are given to the
 * projects they belong to and no others, so neither name means anything in anyone else's code.
 */
class PebbleJsLibraries : JSPredefinedLibraryProvider() {

    override fun getPredefinedLibraries(project: Project): Array<ScriptingLibraryModel> {
        if (project !in forcedForTests && !PebbleProject.getInstance(project).isPebbleProject()) return ScriptingLibraryModel.EMPTY_ARRAY
        return listOfNotNull(
            pebbleKitJs()?.let { ScriptingLibraryModel.createPredefinedLibrary(PEBBLEKIT_JS, arrayOf(it), true) },
            alloyGlobals(project).takeIf { it.isNotEmpty() }?.let { ScriptingLibraryModel.createPredefinedLibrary(ALLOY, it.toTypedArray(), true) },
        ).toTypedArray()
    }

    /**
     * Once a Pebble project has opened. The JavaScript plugin reads its predefined libraries when
     * it starts, which may be before the project's roots say it is a Pebble project.
     */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (!readAction { PebbleProject.getInstance(project).isPebbleProject() }) return
            // Usually they are there already: asking again means a rescan of the project's libraries.
            if (readAction { upToDate(project) }) return
            edtWriteAction { if (!project.isDisposed) reread(project) }
        }
    }

    /** An Alloy build may name other typings than the last one, or name them for the first time. */
    class BuildListener : PebbleBuild.Listener {
        override fun buildFinished(project: Project, root: Path, result: BuildResult) {
            if (PebbleProject.getInstance(project).infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return
            // On the build's thread: the JavaScript plugin's libraries are read under a read action.
            if (runReadActionBlocking { upToDate(project) }) return
            ApplicationManager.getApplication().invokeLater({ WriteAction.run<RuntimeException> { reread(project) } }, project.disposed)
        }
    }

    companion object {
        const val PEBBLEKIT_JS = "PebbleKit JS"
        const val ALLOY = "Alloy globals"

        fun pebbleKitJs(): VirtualFile? =
            PebbleLibraryProvider::class.java.getResource(PebbleLibraryProvider.PEBBLEKIT_JS)?.let(VfsUtil::findFileByURL)

        /** An Alloy project's global typings, as the build checks the app against them; none for another. */
        fun alloyGlobals(project: Project): List<VirtualFile> {
            alloyForTests[project]?.let { return it }
            val model = PebbleProject.getInstance(project)
            val root = model.primaryRoot() ?: return emptyList()
            if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return emptyList()
            return AlloyGlobals.files(root, model.activePlatform(root), PebbleSdkService.getInstance().currentSdk())
                .mapNotNull { LocalFileSystem.getInstance().findFileByNioFile(it) }
        }

        /** Asks the JavaScript plugin for its predefined libraries again, from any thread. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                WriteAction.run<RuntimeException> { reread(project) }
            }
        }

        /** Whether the JavaScript plugin has the libraries this project should have, as they are now. */
        private fun upToDate(project: Project): Boolean {
            val libraries = JSLibraryManager.getInstance(project)
            if (libraries.getLibraryByName(PEBBLEKIT_JS) == null) return false
            val alloy = alloyGlobals(project).toSet()
            return alloy == libraries.getLibraryByName(ALLOY)?.sourceFiles.orEmpty()
        }

        /** Under a write action. */
        private fun reread(project: Project) {
            JSLibraryManager.getInstance(project).apply {
                reset()
                // A library is what changed, and its files may not be indexed yet.
                commitChanges(RootsChangeRescanningInfo.RESCAN_DEPENDENCIES_IF_NEEDED)
            }
        }

        /** A light test's project lives in memory, where no `package.json` makes it a Pebble one. */
        private val forcedForTests: MutableSet<Project> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

        @TestOnly
        fun treatAsPebbleProject(project: Project, disposable: Disposable) {
            forcedForTests += project
            Disposer.register(disposable) { forcedForTests -= project }
        }

        /** And as an Alloy one, with [files] for its global typings in place of an SDK's. */
        private val alloyForTests: MutableMap<Project, List<VirtualFile>> = Collections.synchronizedMap(WeakHashMap())

        @TestOnly
        fun treatAsAlloyProject(project: Project, files: List<VirtualFile>, disposable: Disposable) {
            treatAsPebbleProject(project, disposable)
            alloyForTests[project] = files
            Disposer.register(disposable) { alloyForTests -= project }
        }
    }
}
