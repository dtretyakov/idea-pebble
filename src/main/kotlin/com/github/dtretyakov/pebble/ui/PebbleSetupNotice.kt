package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.EmulatorCheck
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Key
import com.intellij.ui.EditorNotifications

/**
 * Says, when a Pebble project opens, that nothing in it can be built or run on the emulator yet —
 * and offers the fix.
 *
 * The editor banner says the same, but only above a file, and a project opens as often with no
 * file open as with one: the IDE then showed nothing at all, apart from a red cross on the run
 * configuration's icon. A problem with the whole project, found as it opens, is the platform's
 * case for a balloon — Gradle's "Invalid Gradle JDK" is the same shape — while the banner stays
 * for the file in front of the user.
 *
 * One balloon per step. Installing pebble-tool retires the pebble-tool balloon and brings the SDK
 * one; fixing the last problem retires it, wherever the fix was made.
 */
object PebbleSetupNotice {

    const val NOTIFICATION_GROUP = "Pebble Setup"

    /**
     * Re-reads the machine for [project]: retires a balloon whose problem is gone, and shows one
     * for a problem not yet shown. [opening] shows it even when this session already has — the
     * project has just opened, and that is the moment it is for.
     */
    fun reconsider(project: Project, opening: Boolean = false) {
        if (project.isDisposed) return
        // Building first, then running: the emulator is what Run uses, and it is the step after.
        val problem = PebbleEnvironment.firstRunProblem(PebbleEnvironment.check())

        // By what the problem is and what fixes it, not by concern alone: a wrong pebble-tool path
        // cleared becomes "not set up", the same concern with another message and another button.
        val kind = problem?.let(::kindOf)
        val shown = NotificationsManager.getNotificationsManager().getNotificationsOfType(SetupNotification::class.java, project).toList()
        shown.filter { it.kind != kind }.forEach(Notification::expire)

        if (problem == null || isSilenced(project, problem.concern)) {
            project.putUserData(LAST_SHOWN, null)
            return
        }
        if (shown.any { it.kind == kind && !it.isExpired }) return
        if (!opening && project.getUserData(LAST_SHOWN) == kind) return

        project.putUserData(LAST_SHOWN, kind)
        SetupNotification(problem, project).notify(project)
    }

    /** Silenced by "Don't show again" here or by closing the banner: one choice, both surfaces. */
    fun isSilenced(project: Project, concern: Concern): Boolean =
        PropertiesComponent.getInstance(project).getValue(SILENCED) == concern.name

    fun silence(project: Project, concern: Concern) {
        PropertiesComponent.getInstance(project).setValue(SILENCED, concern.name)
    }

    private class SetupNotification(problem: PebbleEnvironment.Item, project: Project) : Notification(
        NOTIFICATION_GROUP,
        SetupMessage.title(problem),
        SetupMessage.body(problem),
        NotificationType.WARNING,
    ) {
        val concern: Concern = problem.concern
        val kind: String = kindOf(problem)

        init {
            // A suggestion: its first action is drawn as a button, which is what "install the
            // missing thing" should look like.
            isSuggestionType = true
            problem.fix?.let { fix ->
                // Not expiring: the balloon goes when the problem does (see reconsider), and stays if
                // the install fails.
                addAction(NotificationAction.createSimple(EnvironmentFixes.label(problem)) { EnvironmentFixes.run(project, problem) })
            }
            addAction(NotificationAction.createSimple("Settings") {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, PebbleConfigurable::class.java)
            })
            addAction(NotificationAction.createSimpleExpiring("Don't show again") {
                silence(project, concern)
                EditorNotifications.getInstance(project).updateAllNotifications()
            })
        }
    }

    /** The concern shown this session, so regaining focus does not show it again and again. */
    private val LAST_SHOWN = Key.create<String>("pebble.setupNotice.lastShown")

    private fun kindOf(item: PebbleEnvironment.Item): String = "${item.concern}:${item.fix}"

    /** Shared with the banner, whose close button set it before this balloon existed. */
    private const val SILENCED = "pebble.missingSdkNotification.dismissedConcern"

    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) {
            if (!readAction { PebbleProject.getInstance(project).isPebbleProject() }) return
            reconsider(project, opening = true)
            // Asked now, so Run and the settings page know before they are needed.
            EmulatorCheck.getInstance().refresh(PebbleSdkService.getInstance().currentSdk())
        }
    }
}
