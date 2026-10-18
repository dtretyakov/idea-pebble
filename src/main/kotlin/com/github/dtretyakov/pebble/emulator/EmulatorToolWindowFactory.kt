package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.vfs.LocalFileSystem
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Key
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.ex.ToolWindowManagerListener
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/**
 * The Pebble Emulator tool window: the emulator's screen, live, with its buttons.
 *
 * Available in Pebble projects only, and watching for an emulator only while it is visible.
 */
class EmulatorToolWindowFactory : ToolWindowFactory, DumbAware {

    override suspend fun isApplicableAsync(project: Project): Boolean = PebbleProject.getInstance(project).isPebbleProject()

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = EmulatorPanel(project, toolWindow.disposable)
        val content = SimpleToolWindowPanel(true, true).apply {
            setContent(panel)
            toolbar = ActionManager.getInstance()
                .createActionToolbar(
                    "PebbleEmulator",
                    DefaultActionGroup(SaveScreenshot(project, panel), RecordGif(project, panel, toolWindow.disposable), EmulatorControlActions(project), StopEmulator(project)),
                    true,
                )
                .also { it.targetComponent = panel }
                .component
        }
        val contents = toolWindow.contentManager
        contents.addContent(contents.factory.createContent(content, SCREEN, false))

        // Every platform side by side, from the last capture: the comparison the screen tab cannot make.
        val platforms = PlatformsPanel(project)
        val capture = CaptureAllPlatformsAction().apply {
            // Said, not only drawn: an icon alone did not say what the tab's one action was.
            templatePresentation.putClientProperty(ActionUtil.SHOW_TEXT_IN_TOOLBAR, true)
        }
        val platformsTab = SimpleToolWindowPanel(true, true).apply {
            setContent(platforms)
            toolbar = ActionManager.getInstance()
                .createActionToolbar("PebblePlatforms", DefaultActionGroup(capture), true)
                .also { it.targetComponent = platforms }
                .component
        }
        contents.addContent(contents.factory.createContent(platformsTab, PLATFORMS, false).also { it.putUserData(PLATFORMS_PANEL, platforms) })

        val session = EmulatorSession.getInstance(project)
        if (toolWindow.isVisible) session.startWatching()
        project.messageBus.connect(toolWindow.disposable).subscribe(
            ToolWindowManagerListener.TOPIC,
            object : ToolWindowManagerListener {
                override fun stateChanged(manager: ToolWindowManager) {
                    if (manager.getToolWindow(ID)?.isVisible == true) session.startWatching() else session.stopWatching()
                }
            },
        )
    }

    /**
     * The watch's own pixels, as a PNG in `screenshots/` named as pebble-tool names its own: a
     * screen picked for the app store, for `pebble publish --screenshots`, which takes files whose
     * names start with the platform. It used to ask where, and to name it otherwise.
     */
    private class SaveScreenshot(private val project: Project, private val panel: EmulatorPanel) :
        DumbAwareAction("Save Screenshot", "Saves the emulator's screen to the project's screenshots folder", AllIcons.Actions.MenuSaveall) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            // The last frame stays on screen after the emulator stops; there is nothing to name it for then.
            val session = EmulatorSession.getInstance(project)
            e.presentation.isEnabled = session.lastFrame != null && session.emulator != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            val image = panel.screenshot() ?: return
            val emulator = EmulatorSession.getInstance(project).emulator ?: return
            val root = PebbleProject.getInstance(project).primaryRoot() ?: return
            val directory = root.resolve(PlatformScreenshots.DIRECTORY).createDirectories()
            val version = PebbleProject.getInstance(project).infoAt(root)?.version
            // Named to the second: a second save within it takes the next free second rather than the first's file.
            val file = generateSequence(LocalDateTime.now()) { it.plusSeconds(1) }
                .map { directory.resolve(PlatformScreenshots.fileName(emulator.platform, version, it)) }
                .first { !it.exists() }
            ImageIO.write(image, "png", file.toFile())
            val saved = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)
            NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
                .createNotification("Saved ${PlatformScreenshots.DIRECTORY}/${file.fileName}.", NotificationType.INFORMATION)
                .apply { if (saved != null) addAction(NotificationAction.createSimpleExpiring("Open") { FileEditorManager.getInstance(project).openFile(saved, true) }) }
                .notify(project)
        }
    }

    /**
     * Records the screen while held down, then asks where to save the GIF. Frames are the
     * emulator's own updates, so the recording follows the screen and costs nothing while it is
     * still.
     */
    private class RecordGif(private val project: Project, private val panel: EmulatorPanel, parent: Disposable) :
        ToggleAction("Record GIF", "Records the emulator's screen as an animated GIF", AllIcons.Debugger.Db_set_breakpoint),
        DumbAware {

        private val recorder = GifRecorder()

        init {
            EmulatorSession.getInstance(project).addListener(
                object : EmulatorSession.Listener {
                    override fun frame(emulator: RunningEmulator, image: BufferedImage) {
                        if (!recorder.recording) return
                        panel.screenshot()?.let { if (!recorder.add(it)) ApplicationManager.getApplication().invokeLater(::save) }
                    }

                    override fun idle() = Unit
                },
                parent,
            )
        }

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun update(e: AnActionEvent) {
            super.update(e)
            e.presentation.isEnabled = recorder.recording || EmulatorSession.getInstance(project).emulator != null
            e.presentation.text = if (recorder.recording) "Stop Recording" else "Record GIF"
        }

        override fun isSelected(e: AnActionEvent): Boolean = recorder.recording

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            if (state) {
                recorder.start()
                panel.screenshot()?.let(recorder::add)
            } else {
                save()
            }
        }

        private fun save() {
            val frames = recorder.stop()
            if (frames.isEmpty()) return
            val platform = EmulatorSession.getInstance(project).emulator?.platform ?: "pebble"
            val name = "$platform-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.gif"
            val target = FileChooserFactory.getInstance()
                .createSaveFileDialog(FileSaverDescriptor("Save Recording", "", "gif"), project)
                .save(project.guessProjectDir(), name)
                ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                target.file.outputStream().buffered().use { GifWriter.write(frames, it) }
            }
        }
    }

    /** `pebble kill`: every emulator pebble-tool started, the way it would stop them itself. */
    private class StopEmulator(private val project: Project) :
        DumbAwareAction("Stop Emulator", "Stops the emulator with pebble kill", AllIcons.Actions.Suspend) {

        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = EmulatorSession.getInstance(project).emulator != null
        }

        override fun actionPerformed(e: AnActionEvent) {
            if (!PebbleCommands.run(project, "Stopping the Pebble emulator", listOf("kill"), "The emulator is stopped.", quiet = true)) {
                NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
                    .createNotification("pebble-tool is not installed.", NotificationType.ERROR)
                    .notify(project)
            }
        }
    }

    companion object {
        const val ID = "Pebble Emulator"

        /** Brings the window up without taking focus from the editor — after a run starts an emulator. */
        private const val SCREEN = "Screen"
        /** The app on every platform side by side, where the Screen tab shows one. */
        private const val PLATFORMS = "All Platforms"
        private val PLATFORMS_PANEL = Key.create<PlatformsPanel>("Pebble.PlatformsPanel")

        /** The All Platforms tab, read again and shown: after a capture. */
        fun showPreview(project: Project) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(ID) ?: return
            window.show {
                val content = window.contentManager.findContent(PLATFORMS) ?: return@show
                content.getUserData(PLATFORMS_PANEL)?.reload()
                window.contentManager.setSelectedContent(content)
            }
        }

        fun reveal(project: Project) {
            ToolWindowManager.getInstance(project).invokeLater {
                ToolWindowManager.getInstance(project).getToolWindow(ID)?.show()
                EmulatorSession.getInstance(project).lookNow()
            }
        }
    }
}
