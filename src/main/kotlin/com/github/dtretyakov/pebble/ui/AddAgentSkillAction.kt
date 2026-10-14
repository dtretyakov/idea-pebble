package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.agent.AgentSkill
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem

/**
 * Tools | Add Pebble Skill for Agents: the `pebble` Agent Skill written into the project, so a
 * coding agent working in it knows the Pebble tools of the IDE's MCP server, the loop of build,
 * run, look and fix, and the platforms. In version control, every agent of the team gets it.
 */
class AddAgentSkillAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null &&
            runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        val changes = AgentSkill.changes(root)
        if (changes.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(
                project,
                "${AgentSkill.DIRECTORY} differs from the plugin's version in ${changes.joinToString()}. Replace it, losing those changes?",
                "Add Pebble Skill for Agents",
                "Replace",
                "Cancel",
                Messages.getQuestionIcon(),
            )
            if (answer != Messages.YES) return
        }
        val skill = AgentSkill.write(root)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(skill)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve(AgentSkill.DIRECTORY)), true, true, null)
        file?.let { FileEditorManager.getInstance(project).openFile(it, true) }
        NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP).createNotification(
            "Pebble skill added for agents",
            "Claude Code finds it in ${AgentSkill.DIRECTORY}; for another agent, copy it to that agent's skills directory. " +
                "The tools it names come from the IDE's MCP server: Settings | Tools | MCP Server.",
            NotificationType.INFORMATION,
        ).notify(project)
    }
}
