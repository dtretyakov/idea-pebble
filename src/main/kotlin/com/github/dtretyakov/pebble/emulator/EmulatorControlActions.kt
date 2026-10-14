package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project

/**
 * The emulator's world, from the tool window's toolbar: battery, Bluetooth, the clock, the
 * accelerometer, the compass, Timeline Quick View and the content size. One menu, because these
 * are tried once each while testing a watchface, not used all day.
 */
internal class EmulatorControlActions(private val project: Project) :
    DefaultActionGroup("Emulator Settings", "Battery, Bluetooth, time, motion and display", AllIcons.General.GearPlain) {

    init {
        isPopup = true
        add(group("Battery",
            listOf(100, 80, 50, 20, 5).map { control("$it%", EmulatorControl.Battery(it, charging = false)) } +
                control("Charging", EmulatorControl.Battery(80, charging = true))))
        add(group("Bluetooth",
            listOf(control("Connected", EmulatorControl.Bluetooth(true)), control("Disconnected", EmulatorControl.Bluetooth(false)))))
        add(group("Clock",
            listOf(
                control("12-Hour", EmulatorControl.TimeFormat(false)),
                control("24-Hour", EmulatorControl.TimeFormat(true)),
                Separator.getInstance(),
                Action("Set to This Computer's Time") { EmulatorControl.SetTime(System.currentTimeMillis() / 1000) },
            )))
        add(group("Tap", EmulatorControl.Tap.DIRECTIONS.map { control(it, EmulatorControl.Tap(it)) }))
        add(group("Tilt", EmulatorControl.Tilt.MOTIONS.map { control(it.replace('-', ' ').replaceFirstChar(Char::uppercase), EmulatorControl.Tilt(it)) }))
        add(group("Compass", listOf("North" to 0, "East" to 90, "South" to 180, "West" to 270).map { (name, heading) ->
            control(name, EmulatorControl.Compass(heading))
        }))
        add(group("Timeline Quick View",
            listOf(control("Show", EmulatorControl.TimelineQuickView(true)), control("Hide", EmulatorControl.TimelineQuickView(false)))))
        add(group("Content Size", EmulatorControl.ContentSize.SIZES.map { control(it.replaceFirstChar(Char::uppercase), EmulatorControl.ContentSize(it)) }))
    }

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = EmulatorSession.getInstance(project).emulator != null
    }

    private fun group(name: String, actions: List<AnAction>): ActionGroup =
        DefaultActionGroup(name, actions).apply { isPopup = true }

    private fun control(name: String, control: EmulatorControl): AnAction = Action(name) { control }

    private inner class Action(name: String, private val control: () -> EmulatorControl) : DumbAwareAction(name) {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = EmulatorSession.getInstance(project).emulator != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val platform = EmulatorSession.getInstance(project).emulator?.platform ?: return
            val root = PebbleProject.getInstance(project).primaryRoot()
            PebbleCommands.run(
                project, "Setting the emulator's ${templateText.lowercase()}", control().commandLine(platform),
                success = "", quiet = true, workDirectory = root,
            )
        }
    }
}
