package com.github.dtretyakov.pebble.emulator

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path

/**
 * Starts the emulator a run installs on, and waits until it can take an app, before
 * `pebble install` runs.
 *
 * pebble-tool starts the emulator itself when `install` names one, and then gives pypkjs — the
 * emulated phone the app is installed through — ten tries half a second apart. A cold start takes
 * longer than that, the first after pebble-tool is installed most of all, so the first Run of the
 * day ended in `[Errno 61] Connection refused` while the emulator came up on screen regardless.
 * Here the same start is made by `pebble ping`, which leaves the emulator running when it gives up
 * just as `install` does, and the wait is as long as a start takes. Install then finds it up.
 *
 * Off the UI thread: from the before-launch task, which has a pooled thread to itself.
 */
object EmulatorWarmUp {

    /** Returns once the emulator for [platform] takes installs, or [WAIT_MILLIS] have gone. */
    fun ensureUp(tool: Path, root: Path, platform: String, sdkVersion: String?, indicator: ProgressIndicator?) {
        if (ready(EmulatorState.running(), platform, sdkVersion, ::listening) != null) return

        indicator?.text = "Starting the $platform emulator"
        val arguments = listOf("ping") + PebbleInstallCommand.connection(PebbleRunTarget.EMULATOR, platform, "", "")
        val output = CapturingProcessHandler(PebbleCommands.command(tool, arguments, root)).let { handler ->
            if (indicator != null) handler.runProcessWithProgressIndicator(indicator, WAIT_MILLIS) else handler.runProcess(WAIT_MILLIS)
        }
        if (output.isCancelled) return
        if (output.exitCode == 0) return

        // Gave up before pypkjs listened, as `install` would have: wait for it the rest of the way.
        val until = System.currentTimeMillis() + WAIT_MILLIS
        while (System.currentTimeMillis() < until) {
            if (indicator?.isCanceled == true) return
            if (ready(EmulatorState.running(), platform, sdkVersion, ::listening) != null) return
            Thread.sleep(POLL_MILLIS)
        }
        LOG.info("The $platform emulator did not take installs within ${WAIT_MILLIS / 1000} s: ${output.stderr.ifBlank { output.stdout }}")
    }

    /**
     * The emulator for [platform] that `pebble install --vnc` would use as it is: on the screen
     * the IDE shows (VNC), its SDK's — when the SDK is known — and with pypkjs listening.
     */
    fun ready(
        running: List<RunningEmulator>,
        platform: String,
        sdkVersion: String?,
        listening: (Int) -> Boolean,
        isAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
    ): RunningEmulator? = running.firstOrNull { emulator ->
        emulator.platform == platform &&
            emulator.vnc &&
            (sdkVersion == null || emulator.sdkVersion == sdkVersion) &&
            emulator.pypkjsPid?.let(isAlive) == true &&
            emulator.pypkjsPort?.let(listening) == true
    }

    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MILLIS) }
    }.isSuccess

    /** A cold start is some seconds; this is room for a slow machine, not an expected wait. */
    private const val WAIT_MILLIS = 60_000
    private const val POLL_MILLIS = 250L
    private const val CONNECT_MILLIS = 200

    private val LOG = logger<EmulatorWarmUp>()
}
