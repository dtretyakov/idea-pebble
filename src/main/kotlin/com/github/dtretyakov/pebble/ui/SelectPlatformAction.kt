package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.sdk.PebblePlatform
import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.Toggleable
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.CustomComponentAction
import com.intellij.openapi.actionSystem.impl.ActionButtonWithText
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBDimension
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.Insets
import javax.swing.JComponent
import javax.swing.SwingConstants

/**
 * The active platform, in the run widget beside the run configuration.
 *
 * A Pebble app is one binary per platform, and switching between a square black-and-white screen
 * and a round colour one is most of what testing a watchface is — so the choice sits beside Run
 * rather than in a dialog. Build, Run and the compile database all read it.
 *
 * Drawn as the run configuration chip next to it is drawn — a toggle that opens a popup
 * underneath, as an [ActionButtonWithText] with a drop-down arrow — rather than as a
 * `ComboBoxAction`, whose bordered button reads as a foreign object among flat neighbours. The
 * platform's chip extends `TogglePopupAction`, which is internal API, so the few lines of it this
 * needs are here on [ToggleAction].
 */
class SelectPlatformAction : ToggleAction(), CustomComponentAction, DumbAware {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        super.update(e)
        val project = e.project
        val root = project?.let { runReadActionBlocking { PebbleProject.getInstance(it).primaryRoot() } }
        e.presentation.isEnabledAndVisible = root != null
        if (project == null || root == null) return

        val platform = PebbleProject.getInstance(project).activePlatform(root)
        e.presentation.setText(platform ?: "No platform", false)
        e.presentation.icon = PebbleIcons.PEBBLE
        e.presentation.description = "The platform Build, Run and code insight use"
    }

    /** Pressed while the popup is open, so the chip reads as held down until it closes. */
    override fun isSelected(e: AnActionEvent): Boolean = Toggleable.isSelected(e.presentation)

    override fun setSelected(e: AnActionEvent, state: Boolean) {
        if (!state) return
        val component = e.inputEvent?.component as? JComponent ?: return
        val group = actions(e.project ?: return) ?: return
        val presentation = e.presentation
        JBPopupFactory.getInstance()
            .createActionGroupPopup(null, group, e.dataContext, false, true, false,
                { Toggleable.setSelected(presentation, false) }, -1, null)
            .apply { setMinimumSize(JBDimension(POPUP_MINIMUM_WIDTH, 0)) }
            .showUnderneathOf(component)
    }

    private fun actions(project: Project): ActionGroup? {
        val model = PebbleProject.getInstance(project)
        val root = runReadActionBlocking { model.primaryRoot() } ?: return null
        return DefaultActionGroup(model.platforms(root).map { Select(project, it) })
    }

    override fun createCustomComponent(presentation: Presentation, place: String): JComponent =
        PlatformButton(this, presentation, place)

    private class Select(private val project: Project, private val platform: String) : AnAction(platform) {
        init {
            PebblePlatform.of(platform)?.let { known ->
                templatePresentation.putClientProperty(ActionUtil.SECONDARY_TEXT, describe(known))
            }
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(event: AnActionEvent) =
            PebbleProject.getInstance(project).setActivePlatform(platform)
    }

    private companion object {
        const val POPUP_MINIMUM_WIDTH = 310

        fun describe(platform: PebblePlatform): String = buildList {
            add(platform.watches)
            add("${platform.width}×${platform.height}")
            if (platform.round) add("round")
            add(if (platform.color) "colour" else "black and white")
        }.joinToString(", ")
    }
}

/** The chip: icon, name, drop-down arrow, laid out like the run configuration one beside it. */
private class PlatformButton(action: AnAction, presentation: Presentation, place: String) :
    ActionButtonWithText(action, presentation, place, JBUI.size(24, 26)) {

    init {
        setHorizontalTextAlignment(SwingConstants.LEFT)
    }

    override fun getMargins(): Insets = JBInsets(0, 10, 0, 6)

    override fun shallPaintDownArrow(): Boolean = true
}
