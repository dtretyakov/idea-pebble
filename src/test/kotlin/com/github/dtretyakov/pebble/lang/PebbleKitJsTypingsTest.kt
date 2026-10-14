package com.github.dtretyakov.pebble.lang

import com.github.dtretyakov.pebble.library.PebbleLibraryProvider
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.openapi.vfs.VfsUtil

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
}
