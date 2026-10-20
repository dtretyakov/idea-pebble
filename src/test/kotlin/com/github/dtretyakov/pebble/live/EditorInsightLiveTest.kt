package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.psi.PsiManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * One of the roadmap's early risks, settled in the IDE itself against the real SDK: an Alloy
 * TypeScript source sees the SDK's typings, which the plugin adds as a library — an import of
 * `commodetto/Poco` resolves into them once the plugin's `tsconfig.json` extends the one the build
 * writes for itself; without it, it resolves to nothing.
 *
 * A project of its own, which goes with the test: the JavaScript plugin keeps a pointer to the
 * app's `package.json` for as long as the project lives.
 */
class EditorInsightLiveTest : HeavyPlatformTestCase() {

    /** The sandbox's plugins, which the IDE reads as the project opens; as in IdeTestCase. */
    override fun setUp() {
        VfsRootAccess.allowRootAccess(testRootDisposable, com.intellij.openapi.application.PathManager.getPluginsPath())
        super.setUp()
    }

    private fun open(root: Path) {
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
    }

    fun `test an Alloy TypeScript source resolves the SDK's modules from its typings`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        LiveSdk.tool()
        val sdk = LiveSdk.sdks().current!!
        val typings = sdk.toolchain.resolve("moddable/typings").toRealPath()
        VfsRootAccess.allowRootAccess(testRootDisposable, typings.toString())
        val root = LiveSdk.fixture("fixture-alloy").toRealPath()
        val source = root.resolve("src/embeddedjs/drawing.ts")
        source.writeText("import Poco from \"commodetto/Poco\";\n\nexport function make(screen: any) {\n  return new Poco(screen);\n}\n")
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
        // What the plugin does after a build: a tsconfig.json extending the build's own settings.
        assertTrue(com.github.dtretyakov.pebble.project.TypeScriptConfig.update(root, "emery", LiveSdk.host))
        // And the build does not mind it.
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
        open(root)
        IndexingTestUtil.waitUntilIndexesAreReady(project)

        val file = PsiManager.getInstance(project).findFile(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!)!!
        // The module the import names: its string resolves to where the module is declared.
        val module = file.text.indexOf("commodetto/Poco") + 2
        val references = generateSequence(file.findElementAt(module)) { it.parent }.take(4).flatMap { it.references.asSequence() }.toList()
        val targets = references.flatMap { reference ->
            if (reference is com.intellij.psi.PsiPolyVariantReference) reference.multiResolve(false).mapNotNull { it.element } else listOfNotNull(reference.resolve())
        }
        assertTrue("\"commodetto/Poco\" resolves to nothing: $references", targets.isNotEmpty())
        val declaredIn = targets.mapNotNull { it.containingFile?.virtualFile?.path?.let(Path::of) ?: (it as? com.intellij.psi.PsiFileSystemItem)?.virtualFile?.path?.let(Path::of) }
        assertTrue("$declaredIn is not in the SDK's typings", declaredIn.any { it.startsWith(typings) })
    }
}
