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

        PebbleKitJsLibrary.treatAsPebbleProject(project, testRootDisposable)
        PebbleKitJsLibrary.refresh(project)
        IndexingTestUtil.waitUntilIndexesAreReady(project)
        assertEquals(listOf("pebblekit-js.d.ts"), targetsOfPebble("src/pkjs/index.js"))
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
