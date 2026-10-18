package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A screenshot of the app on every platform it targets, with pebble-tool's own
 * `screenshot --all-platforms`: one emulator after another, the clock at 10:10, the files moved
 * from `screenshots/` to the [PlatformPreview]. Then the All Platforms tab, to compare them.
 *
 * pebble-tool starts those emulators without VNC, and an emulator started with VNC is restarted
 * without it — so the one on screen stops. That, and the time it takes, are said where the action
 * is offered — its description, the tab's empty state — rather than in a dialog after the click;
 * and the emulator that was on screen is started again when the capture is done.
 */
class CaptureAllPlatformsAction : DumbAwareAction(TEXT, DESCRIPTION, AllIcons.FileTypes.Image) {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible =
            project != null && runReadActionBlocking { PebbleProject.getInstance(project).primaryRoot() } != null
        e.presentation.isEnabled = e.presentation.isEnabledAndVisible && !running.get()
    }

    override fun actionPerformed(e: AnActionEvent) {
        start(e.project ?: return)
    }

    companion object {
        const val TEXT = "Capture Every Platform"
        private const val DESCRIPTION =
            "A screenshot of the app on every platform it targets, side by side — about 15 seconds a platform; " +
                "the emulator on the Screen tab restarts"

        private val running = AtomicBoolean(false)

        /** What a capture costs, for beside the link that starts one. */
        fun cost(platforms: Int): String {
            val seconds = 15 * platforms.coerceAtLeast(1)
            return "About $seconds seconds; the emulator on the Screen tab restarts."
        }

        fun start(project: Project) {
            val root = PebbleProject.getInstance(project).primaryRoot() ?: return
            val tool = PebbleSdkService.getInstance().tool() ?: return PebbleCommands.notify(project, "pebble-tool is not installed.", NotificationType.ERROR)
            if (!running.compareAndSet(false, true)) return
            FileDocumentManager.getInstance().saveAllDocuments()
            capture(project, tool, root, shown = EmulatorSession.getInstance(project).emulator)
        }

        private fun capture(project: Project, tool: Path, root: Path, shown: RunningEmulator?) {
            object : Task.Backgroundable(project, "Capturing every platform", true) {
                private var failed = ""

                override fun run(indicator: ProgressIndicator) {
                    val before = PlatformPreview.before(root)
                    val handler = OSProcessHandler(PebbleCommands.command(tool, listOf("screenshot", "--all-platforms", "--no-open"), root))
                    val output = StringBuilder()
                    handler.addProcessListener(
                        object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                if (outputType == ProcessOutputTypes.SYSTEM) return
                                output.append(event.text)
                                CAPTURING.find(event.text)?.let { indicator.text = "Capturing ${it.groupValues[1]}" }
                            }
                        },
                    )
                    handler.startNotify()
                    while (!handler.waitFor(200)) if (indicator.isCanceled) handler.destroyProcess()
                    if (handler.exitCode != 0 && !indicator.isCanceled) {
                        failed = output.lines().lastOrNull { it.isNotBlank() }.orEmpty()
                    }
                    PlatformPreview.collect(root, before)
                    LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve(PlatformScreenshots.DIRECTORY), PlatformPreview.directory(root)), false, true, null)

                    // The emulator that was on the Screen tab, back as it was: pebble-tool stopped it.
                    if (shown != null && !indicator.isCanceled) {
                        indicator.text = "Starting the ${shown.platform} emulator again"
                        EmulatorWarmUp.ensureUp(tool, root, shown.platform, shown.sdkVersion, indicator)
                        EmulatorSession.getInstance(project).lookNow()
                    }
                }

                override fun onFinished() {
                    running.set(false)
                }

                override fun onSuccess() {
                    if (failed.isNotEmpty()) PebbleCommands.notify(project, "Capturing every platform failed: $failed", NotificationType.ERROR)
                    EmulatorToolWindowFactory.showPreview(project)
                }
            }.queue()
        }


        private val CAPTURING = Regex("""=== Capturing (\S+) ===""")
    }
}
