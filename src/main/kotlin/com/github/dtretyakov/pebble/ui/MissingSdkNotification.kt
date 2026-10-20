package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.CompileDatabase
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectLoad
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.ui.EditorNotificationPanel
import com.intellij.ui.EditorNotificationProvider
import com.intellij.ui.EditorNotifications
import java.nio.file.Path
import java.util.function.Function
import javax.swing.JComponent

/**
 * Says so, in the editor of any file inside a Pebble project, when nothing could be built — and,
 * above the files they matter to, what is missing for less than that: Node.js above PebbleKit JS,
 * clangd above C in an IDE without a C engine.
 *
 * Without it the failure is invisible until the first build, and the first build is exactly the
 * moment a newcomer is least able to read a waf stack trace. Two links at most — the one that
 * fixes it and the settings page — and a close button that is remembered.
 *
 * The things that are only sometimes needed are said only where they are: a project without
 * PebbleKit JS has no use for Node.js, and saying so at every file would teach the user to close
 * banners unread.
 *
 * Asked again whenever the IDE regains focus (see [EnvironmentRecheck]): pebble-tool and SDKs are
 * as often installed from a terminal as from here.
 */
class MissingSdkNotification : EditorNotificationProvider {

    override fun collectNotificationData(
        project: Project,
        file: VirtualFile,
    ): Function<in FileEditor, out JComponent?>? {
        val root = PebbleProject.getInstance(project).rootFor(file) ?: return null

        val items = PebbleEnvironment.check()
        // "Loading" only where loading is all that is missing: without clangd, or LSP4IJ, the
        // build would finish and still leave C without code insight, and that is the thing to say.
        if (PebbleEnvironment.firstProblem(items) == null && file.extension in C_EXTENSIONS && aboutThisFile(items, root, file) == null) {
            notLoaded(project)?.let { return it }
        }
        val problem = PebbleEnvironment.firstProblem(items) ?: aboutThisFile(items, root, file) ?: return null
        if (problem.pending) return null
        // Closing the banner silences this problem, not every later one: someone who chose to
        // live without pebble-tool for now still wants to hear that the SDK is missing once
        // they have it.
        if (PebbleSetupNotice.isSilenced(project, problem.concern)) return null

        return Function { _ ->
            val status = if (problem.impact == PebbleEnvironment.Impact.BUILD) EditorNotificationPanel.Status.Error else EditorNotificationPanel.Status.Warning
            EditorNotificationPanel(status).apply {
                text = "<html><b>${SetupMessage.title(problem)}.</b> ${SetupMessage.body(problem)}</html>"
                problem.fix?.let { fix -> createActionLabel(EnvironmentFixes.label(problem)) { EnvironmentFixes.run(project, problem) } }
                createActionLabel("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, PebbleConfigurable::class.java)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                }
                setCloseAction {
                    PebbleSetupNotice.silence(project, problem.concern)
                    EditorNotifications.getInstance(project).updateAllNotifications()
                    PebbleSetupNotice.reconsider(project)
                }
            }
        }
    }

    /**
     * Above C, while there is no compile database: why there is no code insight yet, rather than
     * nothing at all. It goes when the database is written ([DatabaseWritten]).
     */
    private fun notLoaded(project: Project): Function<in FileEditor, out JComponent?>? {
        if (CompileDatabase.getInstance(project).exists()) return null
        val loading = PebbleProjectLoad.getInstance(project).isLoading
        return Function { _ ->
            EditorNotificationPanel(EditorNotificationPanel.Status.Info).apply {
                if (loading) {
                    text = "Loading the Pebble project: one build, to learn how the C is compiled. Code insight starts when it is done."
                } else {
                    text = "C has no code insight until the project has been built once: the build is what knows its flags."
                    createActionLabel("Build") { PebbleProjectLoad.getInstance(project).load() }
                }
            }
        }
    }

    /** Takes the "loading" banner back once the database is there. */
    class DatabaseWritten : CompileDatabase.Written {
        override fun written(project: Project) = EditorNotifications.getInstance(project).updateAllNotifications()
    }

    /** What is missing that only this file needs, when nothing more is in the way. */
    private fun aboutThisFile(items: List<PebbleEnvironment.Item>, root: Path, file: VirtualFile): PebbleEnvironment.Item? {
        fun missing(concern: PebbleEnvironment.Concern) =
            PebbleEnvironment.of(items, concern)?.takeIf { it.status == PebbleEnvironment.Status.MISSING }

        val path = file.toNioPathOrNull() ?: return null
        return when {
            path.startsWith(root.resolve("src/pkjs")) -> missing(PebbleEnvironment.Concern.NODE)
            file.extension in C_EXTENSIONS -> missing(PebbleEnvironment.Concern.C_INSIGHT)
            else -> null
        }
    }

    private companion object {
        val C_EXTENSIONS = setOf("c", "h")
    }
}
