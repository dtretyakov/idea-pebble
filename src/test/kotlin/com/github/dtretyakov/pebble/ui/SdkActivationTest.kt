package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.openapi.util.Disposer

/** Which SDK is active is chosen in the list and waits for Apply; Reset and Cancel forget the choice. */
class SdkActivationTest : IdeTestCase() {

    fun `test a chosen SDK waits for Apply, and Reset forgets it`() {
        val disposable = Disposer.newDisposable()
        try {
            val panel = SdkListPanel(project, disposable)
            assertFalse(panel.isModified())

            panel.choose("0.0.1-not-active")
            assertTrue(panel.isModified())

            panel.reset()
            assertFalse(panel.isModified())

            PebbleSdkService.getInstance().currentSdk()?.let { active ->
                panel.choose(active.version)
                assertFalse("choosing the active SDK changes nothing", panel.isModified())
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }
}
