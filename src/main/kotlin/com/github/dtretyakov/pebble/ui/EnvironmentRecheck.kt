package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.EmulatorCheck
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.EditorNotifications

/**
 * Asks the banner and the setup balloon again when the user comes back to the IDE.
 *
 * The usual way pebble-tool or an SDK arrives is a terminal, and nothing tells the IDE about it:
 * without this the banner went on saying "not installed" about a tool that was. The checklist
 * reads files and starts no process, so asking on every return costs a few stats.
 */
class EnvironmentRecheck : ApplicationActivationListener {

    override fun applicationActivated(ideFrame: IdeFrame) {
        val project = ideFrame.project?.takeUnless { it.isDisposed } ?: return
        if (!PebbleProject.getInstance(project).isPebbleProject()) return
        EditorNotifications.getInstance(project).updateAllNotifications()
        PebbleSetupNotice.reconsider(project)
        // An emulator that could not start may have had its libraries installed meanwhile.
        EmulatorCheck.getInstance().refresh(PebbleSdkService.getInstance().currentSdk())
    }
}
