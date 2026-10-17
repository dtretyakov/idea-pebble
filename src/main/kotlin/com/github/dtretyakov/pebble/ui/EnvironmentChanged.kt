package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.ProjectManager
import com.intellij.ui.EditorNotifications

/**
 * Passes a change in the environment — the emulator's answer, a command in the terminal taking
 * effect — on to the balloon and the banners of every open Pebble project.
 *
 * The emulator's answer arrives some time after whatever asked for it — a project opening, Set Up
 * finishing, the terminal — and the balloon is where someone who has not opened Settings hears
 * that Run will not work.
 */
class EnvironmentChanged : PebbleEnvironment.Listener {

    override fun changed() {
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed && runReadActionBlocking { PebbleProject.getInstance(it).isPebbleProject() } }
            .forEach { project ->
                PebbleSetupNotice.reconsider(project)
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
    }
}
