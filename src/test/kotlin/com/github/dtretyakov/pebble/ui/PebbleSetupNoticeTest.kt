package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.PebbleSdkSetup
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager

/** The balloon a project opens with when nothing in it can be built. */
class PebbleSetupNoticeTest : IdeTestCase() {

    private var savedToolPath = ""

    override fun setUp() {
        super.setUp()
        // A configured path that is not there: pebble-tool is the first problem on any machine.
        savedToolPath = PebbleAppSettings.getInstance().pebbleToolPath
        PebbleAppSettings.getInstance().pebbleToolPath = "/nonexistent/pebble"
    }

    override fun tearDown() {
        try {
            PebbleAppSettings.getInstance().pebbleToolPath = savedToolPath
            PropertiesComponent.getInstance(project).unsetValue(SILENCED)
            shown().forEach(Notification::expire)
        } finally {
            super.tearDown()
        }
    }

    fun `test opening shows one balloon, and coming back to the IDE does not add another`() {
        PebbleSetupNotice.reconsider(project, opening = true)
        assertEquals(1, shown().size)
        assertTrue(shown().single().title.contains("pebble-tool"))

        PebbleSetupNotice.reconsider(project)
        assertEquals(1, shown().size)
    }

    fun `test don't show again silences the balloon and the banner for that problem`() {
        PebbleSetupNotice.reconsider(project, opening = true)
        PebbleSetupNotice.silence(project, Concern.TOOL)
        PebbleSetupNotice.reconsider(project, opening = true)
        // Still the one already up: silencing stops new ones, the action itself expires its own.
        shown().forEach(Notification::expire)
        PebbleSetupNotice.reconsider(project, opening = true)
        assertEmpty(shown())
        assertTrue(PebbleSetupNotice.isSilenced(project, Concern.TOOL))
    }

    fun `test the balloon goes when its problem does`() {
        PebbleSetupNotice.reconsider(project, opening = true)
        assertEquals(1, shown().size)
        PebbleAppSettings.getInstance().pebbleToolPath = ""
        PebbleSetupNotice.reconsider(project)
        // The wrong path's balloon is gone; on a machine without pebble-tool, Set Up's replaces it.
        assertEmpty(shown().filter { it.title.contains("setting") })
        if (PebbleSdkService.getInstance().tool() == null) {
            assertEquals(listOf("Pebble SDK isn't set up"), shown().map { it.title })
            assertTrue(shown().single().actions.any { it.templateText == PebbleSdkSetup.LABEL })
        }
    }

    private fun shown(): List<Notification> =
        NotificationsManager.getNotificationsManager().getNotificationsOfType(Notification::class.java, project)
            .filter { it.groupId == PebbleSetupNotice.NOTIFICATION_GROUP && !it.isExpired }

    private companion object {
        const val SILENCED = "pebble.missingSdkNotification.dismissedConcern"
    }
}
