package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.library.PebbleLibraryProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.EditorNotifications

/**
 * What follows an SDK being installed, activated or removed, in every open project: the SDK's
 * headers and typings under External Libraries, the compile database (the compiler path in it is
 * the SDK's), and the editor banner, which may no longer have anything to say.
 */
class SdkChanges : PebbleSdkService.Listener {

    override fun sdksChanged() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val sdk = PebbleSdkService.getInstance().currentSdk()
            ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.forEach { project ->
                val model = PebbleProject.getInstance(project)
                val root = model.primaryRoot() ?: return@forEach
                // A new SDK's directories are not in the VFS yet, and the library provider runs
                // under a read action, where it may not refresh them itself.
                if (sdk != null) {
                    val roots = listOfNotNull(model.activePlatform(root)?.let(sdk::includeDir), sdk.toolchain.resolve("moddable/typings"))
                    LocalFileSystem.getInstance().refreshNioFiles(roots, false, false, null)
                }
                PebbleLibraryProvider.refresh(project)
                CompileDatabase.getInstance(project).refresh(force = true)
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }
    }
}
