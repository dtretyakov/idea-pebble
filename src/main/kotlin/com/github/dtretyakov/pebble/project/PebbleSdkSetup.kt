package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.ui.EnvironmentFixes
import com.github.dtretyakov.pebble.ui.PebbleConfigurable
import com.github.dtretyakov.pebble.ui.PebbleSetupNotice
import com.github.dtretyakov.pebble.ui.SetupMessage
import com.github.dtretyakov.pebble.sdk.HostPackages
import com.github.dtretyakov.pebble.sdk.HostTool
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.UvRelease
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Set Up Pebble SDK: whatever is still missing of uv, pebble-tool and an SDK, in one background
 * task, from one button — on the editor banner, the balloon a project opens with, the settings page
 * and the New Project wizard.
 *
 * It does what the SDK's instructions ask the user to do by hand, minus the parts that need them:
 * no shell script piped into a shell, no `sudo`, nothing added to the PATH or a shell profile.
 * What does need them — the emulator's system libraries — is checked at the end and offered as a
 * command to run in a terminal, where they can read it first. The same shape as the platform's
 * Download JDK: say what is missing, install it into a place the IDE manages, report when done.
 */
object PebbleSdkSetup {

    const val LABEL = "Set Up Pebble SDK"

    /** What is still to download, for the sentence beside the button: "uv, pebble-tool and an SDK". */
    fun remaining(): List<String> {
        val service = PebbleSdkService.getInstance()
        return buildList {
            if (service.tool() == null) {
                if (uv() == null) add("uv")
                add("pebble-tool")
            }
            if (service.sdks().installed.isEmpty()) add("the latest SDK")
        }
    }

    /** A user's own uv first; the plugin's, if it has installed one; otherwise none yet. */
    fun uv(): Path? {
        val host = PebbleSdkService.getInstance().host() ?: return null
        return host.locate(HostTool.UV) ?: ManagedUv.locate(host)
    }

    fun start(project: Project?) {
        // One at a time: the banner and the balloon clicked together would race to install the
        // same things into the same places.
        if (!running.compareAndSet(false, true)) return

        object : Task.Backgroundable(project, "Setting up the Pebble SDK", true) {
            private var failure: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                failure = try {
                    setUp(indicator)
                    null
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }

            override fun onFinished() {
                running.set(false)
                // Whatever got done is announced, finished or not: the banner, the balloon and the
                // settings page all read the machine again.
                ApplicationManager.getApplication().messageBus.syncPublisher(PebbleSdkService.TOPIC).sdksChanged()
            }

            override fun onSuccess() {
                val problem = failure
                val sdk = PebbleSdkService.getInstance().currentSdk()
                if (problem != null || sdk == null) {
                    notify(
                        project, "Pebble SDK setup did not finish", problem ?: "No SDK is active.", NotificationType.ERROR,
                        NotificationAction.createSimpleExpiring("Try again") { start(project) },
                        NotificationAction.createSimple("Settings") { openSettings(project) },
                    )
                    return
                }
                val emulator = PebbleEnvironment.of(PebbleEnvironment.check(), PebbleEnvironment.Concern.EMULATOR)
                when {
                    emulator == null || emulator.status == PebbleEnvironment.Status.READY ->
                        notify(project, "Pebble SDK ${sdk.version} is ready", "Build and Run work now.", NotificationType.INFORMATION)
                    // The setup balloon says it, with the fix, in the project that asked: one
                    // message rather than a "ready" that is not true and a correction beside it.
                    project != null -> PebbleSetupNotice.reconsider(project, opening = true)
                    else -> notify(
                        null, "Pebble SDK ${sdk.version} is installed", SetupMessage.body(emulator), NotificationType.WARNING,
                        *listOfNotNull(emulator.fix?.let { NotificationAction.createSimple(EnvironmentFixes.label(emulator)) { EnvironmentFixes.run(null, emulator) } }).toTypedArray(),
                    )
                }
            }
        }.queue()
    }

    /** Everything [start] does in its task, on the calling thread: for a test that walks the setup through. */
    internal fun setUp(indicator: ProgressIndicator) {
        val service = PebbleSdkService.getInstance()
        // Under WSL, what is installed is known only by asking the distribution: asked again here,
        // and after each step, rather than read from an answer that may be minutes old.
        ToolHosts.refreshNow()
        val host = service.host() ?: error("The Pebble SDK runs on Windows inside WSL: install it first (`${PebbleEnvironment.INSTALL_WSL}`).")
        val where = if (host is LocalToolHost) "" else " in ${host.presentableName}"

        if (service.tool() == null) {
            val uv = uv() ?: run {
                indicator.text = "Downloading uv ${UvRelease.VERSION}"
                indicator.fraction = 0.05
                val asset = UvRelease.forPlatform(host.os, host.arch.ifEmpty { System.getProperty("os.arch") })
                    ?: error("There is no uv for ${host.os.name.lowercase()} on ${host.arch.ifEmpty { System.getProperty("os.arch") }}.")
                ManagedUv.install(asset, ManagedUv.directory(host)) { url, to ->
                    HttpRequests.request(url).productNameAsUserAgent().saveToFile(to.toFile(), indicator)
                }.also { uv ->
                    // A Windows drive's files are executable in WSL unless /etc/wsl.conf mounts it
                    // with `metadata`, which keeps Linux modes and so gives this one none.
                    if (host !is LocalToolHost) host.toolPath(uv)?.let { spelled ->
                        runCatching { CapturingProcessHandler(ToolCommandLine.shell("chmod +x " + WslToolHost.shellQuote(spelled), null, host = host)).runProcess(CHMOD_MILLIS) }
                    }
                }
            }

            indicator.text = "Installing pebble-tool$where"
            indicator.text2 = "uv tool install pebble-tool"
            indicator.fraction = 0.2
            // `--python 3.13`: pebble-tool's package allows any Python from 3.10 up, but the SDK
            // supports 3.10 to 3.13, and uv would otherwise pick 3.14 where it is the newest. uv
            // downloads 3.13 itself when it is not there.
            execute(
                ToolCommandLine.build(uv, listOf("tool", "install", "--python", "3.13", "pebble-tool"), host = host),
                indicator, "Installing pebble-tool failed.",
            )
            ToolHosts.refreshNow()
        }

        val tool = service.tool() ?: error(
            "pebble-tool was installed$where, but not where the IDE looks: the PATH, ~/.local/bin, \$UV_TOOL_BIN_DIR or \$XDG_BIN_HOME.",
        )

        val sdks = service.sdks()
        // pebble-tool's installer runs npm and fails without it, after the download: said before it.
        if (sdks.installed.isEmpty() && host.locate(HostTool.NPM) == null) {
            val command = HostPackages.current(host).sdkPrerequisites()
            error("Installing the SDK needs Node.js and npm$where" + (command?.let { ": run `$it`, then Set Up again." } ?: ": install Node.js, then Set Up again."))
        }
        when {
            sdks.current != null -> Unit
            sdks.installed.isNotEmpty() -> {
                indicator.text = "Activating Pebble SDK ${sdks.installed.first().version}"
                execute(PebbleCommands.command(tool, listOf("sdk", "activate", sdks.installed.first().version)), indicator, "Activating the SDK failed.")
            }
            else -> {
                indicator.text = "Downloading the latest Pebble SDK"
                indicator.text2 = "pebble sdk install latest — a few hundred megabytes"
                indicator.fraction = 0.5
                execute(PebbleCommands.command(tool, listOf("sdk", "install", "latest")), indicator, "Installing the SDK failed.")
            }
        }
        ToolHosts.refreshNow()

        // Before saying "ready": the emulator is the part of the SDK that links against the
        // system's libraries, and the one part that can install cleanly and still not work.
        indicator.text = "Checking that the emulator starts"
        indicator.text2 = ""
        indicator.fraction = 0.95
        EmulatorCheck.getInstance().checkNow(service.currentSdk())
        indicator.fraction = 1.0
    }

    /** Runs [command], cancellably, and fails with its last words when it fails. */
    private fun execute(command: GeneralCommandLine, indicator: ProgressIndicator, failed: String) {
        val output = CapturingProcessHandler(
            command.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE).withCharset(Charsets.UTF_8),
        ).runProcessWithProgressIndicator(indicator)
        if (output.isCancelled) throw ProcessCanceledException()
        if (output.exitCode != 0 || output.isTimeout) {
            // Escaped: a Python traceback's `<module>` would otherwise vanish into the markup.
            val lastWords = (output.stderrLines + output.stdoutLines).takeLast(5).joinToString("<br>", transform = PebbleEnvironment::html)
            error("$failed<br>$lastWords")
        }
    }

    private fun notify(project: Project?, title: String, content: String, type: NotificationType, vararg actions: NotificationAction) {
        NotificationGroupManager.getInstance().getNotificationGroup(PebbleCommands.NOTIFICATION_GROUP)
            .createNotification(title, content, type)
            .also { notification: Notification -> actions.forEach(notification::addAction) }
            .notify(project)
    }

    private fun openSettings(project: Project?) =
        ShowSettingsUtil.getInstance().showSettingsDialog(project, PebbleConfigurable::class.java)

    private val running = AtomicBoolean(false)

    private const val CHMOD_MILLIS = 20_000
}
