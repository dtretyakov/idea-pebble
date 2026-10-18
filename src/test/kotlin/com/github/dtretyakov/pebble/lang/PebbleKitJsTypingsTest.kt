package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.library.PebbleLibraryProvider
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.IndexingTestUtil

/** The typings are found where the library provider looks, and the IDE's JavaScript reads them. */
class PebbleKitJsTypingsTest : IdeTestCase() {

    fun `test the typings are where the library provider finds them`() {
        val url = PebbleLibraryProvider::class.java.getResource(PebbleLibraryProvider.PEBBLEKIT_JS)
        assertNotNull(url)
        assertNotNull(VfsUtil.findFileByURL(url!!))
    }

    fun `test the Pebble object completes in PebbleKit JS`() {
        myFixture.addFileToProject(
            "pebblekit-js.d.ts",
            PebbleLibraryProvider::class.java.getResource(PebbleLibraryProvider.PEBBLEKIT_JS)!!.readText(),
        )
        myFixture.configureByText("index.js", "Pebble.send<caret>")
        myFixture.completeBasic()
        val offered = myFixture.lookupElementStrings
        if (offered == null) {
            myFixture.checkResult("Pebble.sendAppMessage(<caret>)")
        } else {
            assertContainsElements(offered, "sendAppMessage", "sendConfigMessage")
        }
    }

    fun `test events are offered by name`() {
        myFixture.addFileToProject(
            "pebblekit-js.d.ts",
            PebbleLibraryProvider::class.java.getResource(PebbleLibraryProvider.PEBBLEKIT_JS)!!.readText(),
        )
        myFixture.configureByText("index.js", "Pebble.addEventListener('<caret>')")
        myFixture.completeBasic()
        assertContainsElements(myFixture.lookupElementStrings.orEmpty(), "ready", "appmessage", "showConfiguration", "webviewclosed")
    }

    /**
     * As shipped, not copied into the project: the library the JavaScript plugin is given — and
     * only in a Pebble project, so `Pebble` means nothing in anyone else's JavaScript.
     */
    fun `test Pebble resolves from the predefined library in a Pebble project, and only there`() {
        assertEmpty(targetsOfPebble("src/pkjs/before.js"))

        PebbleJsLibraries.treatAsPebbleProject(project, testRootDisposable)
        PebbleJsLibraries.refresh(project)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("pebblekit-js.d.ts"), targetsOfPebble("src/pkjs/index.js"))
    }

    /**
     * An Alloy app's `watch`, from typings outside the project as an SDK's are, and declared as
     * the SDK declares it: an interface and a constant of the same name.
     */
    fun `test an Alloy app's watch global resolves from the build's global typings`() {
        val dir = java.nio.file.Files.createTempDirectory("typings")
        java.nio.file.Files.writeString(
            dir.resolve("global.d.ts"),
            "interface watch { addEventListener(event: string, callback: () => void): void; }\ndeclare const watch: watch;\n",
        )
        val typings = com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByNioFile(dir.resolve("global.d.ts"))!!
        val file = myFixture.addFileToProject("src/embeddedjs/main.js", "watch.addEventListener('secondchange', () => {});")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        assertEmpty(targets())

        PebbleJsLibraries.treatAsAlloyProject(project, listOf(typings), testRootDisposable)
        PebbleJsLibraries.refresh(project)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("global.d.ts"), targets())
    }

    private fun targets(): List<String> {
        val reference = myFixture.file.findReferenceAt(1)
        val results = (reference as? PsiPolyVariantReference)?.multiResolve(false).orEmpty()
        return results.filter { it.isValidResult }.mapNotNull { it.element?.containingFile?.name }.distinct()
    }

    private fun targetsOfPebble(path: String): List<String> {
        val file = myFixture.addFileToProject(path, "Pebble.addEventListener('ready', function () {});")
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        val reference = myFixture.file.findReferenceAt(1)
        val results = (reference as? PsiPolyVariantReference)?.multiResolve(false).orEmpty()
        // Valid results only: for a name it cannot resolve, JavaScript still offers weak candidates
        // from anywhere in the index, to navigate to — and highlights the name as unresolved.
        val targets = results.filter { it.isValidResult }.mapNotNull { it.element }
        return targets.mapNotNull { it.containingFile?.name }.distinct()
    }
}
