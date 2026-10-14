package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.ui.components.JBList
import com.intellij.util.ui.UIUtil

/** The settings page is built inside a real IDE: a UI that compiles is not a UI that builds. */
class PebbleConfigurableTest : IdeTestCase() {

    fun `test the page builds, with the SDK list when pebble-tool is here`() {
        val configurable = PebbleConfigurable(project)
        try {
            val component = configurable.createComponent()
            configurable.reset()
            val lists = UIUtil.findComponentsOfType(component, JBList::class.java)
            if (PebbleSdkService.getInstance().tool() != null) {
                assertEquals(PebbleSdkService.getInstance().sdks().installed.size, lists.single().model.size)
            } else {
                assertEmpty(lists)
            }
        } finally {
            configurable.disposeUIResources()
        }
    }
}
