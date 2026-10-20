package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.lang.PebbleJsLibraries
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyToRecursively

/**
 * A project inside a WSL distribution — `\\wsl.localhost\…`, which the IDE reads through its own
 * WSL file system — gets the same JavaScript insight as one on a Windows drive: PebbleKit JS's
 * `Pebble` resolves from the plugin's typings.
 */
class WslProjectInsightLiveTest : HeavyPlatformTestCase() {

    override fun setUp() {
        VfsRootAccess.allowRootAccess(testRootDisposable, com.intellij.openapi.application.PathManager.getPluginsPath())
        super.setUp()
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    fun `test PebbleKit JS resolves in a project inside WSL`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        LiveSdk.tool()
        val host = LiveSdk.host as? WslToolHost ?: return
        val root = host.idePath("${host.probe!!.home}/pebble-live-insight/fixture-c")!!
        val source = Path.of(LiveSdk::class.java.getResource("/fixture-c/package.json")!!.toURI()).parent
        Files.createDirectories(root.parent)
        source.copyToRecursively(root, followLinks = false, overwrite = true)

        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
        PebbleJsLibraries.refresh(project)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val index = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("src/pkjs/index.js"))!!
        val file = PsiManager.getInstance(project).findFile(index)!!
        val at = file.text.indexOf("Pebble") + 1
        val targets = generateSequence(file.findElementAt(at)) { it.parent }.take(3).flatMap { it.references.asSequence() }
            .flatMap { reference ->
                if (reference is PsiPolyVariantReference) reference.multiResolve(false).mapNotNull { it.element }.asSequence() else sequenceOf(reference.resolve()).filterNotNull()
            }
            .mapNotNull { it.containingFile?.name }.toList()
        assertTrue("Pebble resolves to nothing in $root: $targets", "pebblekit-js.d.ts" in targets)
    }
}
