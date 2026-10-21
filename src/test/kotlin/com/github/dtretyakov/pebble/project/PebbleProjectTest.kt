package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Which `package.json` makes a Pebble project, asked on the UI thread — where for a project in WSL
 * every look at the disk is a request to the distribution, so none is made — and off it.
 */
class PebbleProjectTest : IdeTestCase() {

    private fun app(): Path {
        val root = Files.createTempDirectory("pebble-project").resolve("app")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        root.createDirectories().resolve("package.json").writeText("""{"name": "app", "pebble": {"targetPlatforms": ["emery"]}}""")
        return root
    }

    fun `test a project the IDE has not seen is not read from the disk on the UI thread`() {
        val root = app()
        val model = PebbleProject.getInstance(project)
        assertTrue(ApplicationManager.getApplication().isDispatchThread)
        assertNull(model.infoAt(root))
        // Off it, the disk is read; and then the UI thread has that answer to give.
        assertEquals(listOf("emery"), offUiThread { model.infoAt(root) }?.targetPlatforms)
        assertEquals(listOf("emery"), model.infoAt(root)?.targetPlatforms)
    }

    fun `test a project the IDE has seen is answered on the UI thread, and its edits with it`() {
        val root = app()
        VfsUtil.markDirtyAndRefresh(false, true, true, LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!)
        val model = PebbleProject.getInstance(project)
        assertEquals(listOf("emery"), model.infoAt(root)?.targetPlatforms)

        root.resolve("package.json").writeText("""{"name": "app", "pebble": {"targetPlatforms": ["basalt"]}}""")
        LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve("package.json")))
        assertEquals(listOf("basalt"), model.infoAt(root)?.targetPlatforms)

        Files.delete(root.resolve("package.json"))
        VfsUtil.markDirtyAndRefresh(false, false, true, LocalFileSystem.getInstance().findFileByNioFile(root)!!)
        assertNull(model.infoAt(root))
    }
}
