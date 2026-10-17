package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.library.PebbleLibraryProvider
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.lang.javascript.library.JSLibraryManager
import com.intellij.lang.javascript.library.JSPredefinedLibraryProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.RootsChangeRescanningInfo
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.webcore.libraries.ScriptingLibraryModel
import org.jetbrains.annotations.TestOnly
import java.util.Collections
import java.util.WeakHashMap

/**
 * PebbleKit JS's `Pebble` object for the IDE's JavaScript: a predefined library, the JavaScript
 * plugin's own way of shipping typings — the way it ships those in `node_modules`.
 *
 * It used to be an ordinary library under External Libraries, which is indexed and browsable but
 * not where JavaScript resolves a global name from, so `Pebble` was unresolved in every
 * `src/pkjs/index.js`. A predefined library is; and like `node_modules`', it is given to the
 * projects it belongs to and no others, so `Pebble` means nothing in anyone else's JavaScript.
 */
class PebbleKitJsLibrary : JSPredefinedLibraryProvider() {

    override fun getPredefinedLibraries(project: Project): Array<ScriptingLibraryModel> {
        if (project !in forcedForTests && !PebbleProject.getInstance(project).isPebbleProject()) return ScriptingLibraryModel.EMPTY_ARRAY
        val typings = typings() ?: return ScriptingLibraryModel.EMPTY_ARRAY
        return arrayOf(ScriptingLibraryModel.createPredefinedLibrary(NAME, arrayOf(typings), true))
    }

    /**
     * Once a Pebble project has opened. The JavaScript plugin reads its predefined libraries when
     * it starts, which may be before the project's roots say it is a Pebble project.
     */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (!readAction { PebbleProject.getInstance(project).isPebbleProject() }) return
            // Usually it is there already: asking again means a rescan of the project's libraries.
            if (readAction { JSLibraryManager.getInstance(project).getLibraryByName(NAME) } != null) return
            edtWriteAction { if (!project.isDisposed) reread(project) }
        }
    }

    companion object {
        const val NAME = "PebbleKit JS"

        fun typings(): VirtualFile? =
            PebbleLibraryProvider::class.java.getResource(PebbleLibraryProvider.PEBBLEKIT_JS)?.let(VfsUtil::findFileByURL)

        /** Asks the JavaScript plugin for its predefined libraries again, from any thread. */
        fun refresh(project: Project) {
            ApplicationManager.getApplication().invokeAndWait {
                if (project.isDisposed) return@invokeAndWait
                WriteAction.run<RuntimeException> { reread(project) }
            }
        }

        /** Under a write action. */
        private fun reread(project: Project) {
            JSLibraryManager.getInstance(project).apply {
                reset()
                // A library is what changed, and its file may not be indexed yet.
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
    }
}
