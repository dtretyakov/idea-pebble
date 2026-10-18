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
 * Here the same start is made by a command that changes nothing on the watch (`pebble data-logging
 * get-sends-enabled`), which leaves the emulator running when it gives up
 * just as `install` does, and the wait is as long as a start takes. Install then finds it up.
 *
 * An emulator already up is asked too, the same way: QEMU and pypkjs can run on, and
 * pypkjs listen, while the watch's firmware answers nothing — after a debug session cut short, for
 * one — and every install then ends in `App install failed.` with no reason given. One that does
 * not answer is stopped and started afresh; unless a debugging session is on, since a watch
 * stopped at a breakpoint does not answer either, and is no less the user's for it.
 *
 * Off the UI thread: from the before-launch task, which has a pooled thread to itself.
 */
object EmulatorWarmUp {

    /**
     * Returns once the emulator for [platform] takes installs, or [WAIT_MILLIS] have gone.
     * [debugging]: a debug session is on, and a silent watch is not to be restarted.
     */
    fun ensureUp(tool: Path, root: Path, platform: String, sdkVersion: String?, indicator: ProgressIndicator?, debugging: Boolean = false) {
        if (ready(EmulatorState.running(), platform, sdkVersion, ::listening) != null) {
            if (debugging || answers(tool, root, platform, indicator)) return
            indicator?.text = "The $platform emulator does not answer: restarting it"
            LOG.info("The $platform emulator does not answer; restarting it")
            run(tool, root, listOf("kill"), indicator, KILL_MILLIS)
        }

        indicator?.text = "Starting the $platform emulator"
        val output = run(tool, root, ask(platform), indicator, WAIT_MILLIS)
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

    /**
     * Whether the watch answers: a second and a half when it does, nothing when it does not.
     *
     * Not `pebble ping`, which the watch answers by putting up a window saying "Ping" over the app
     * the run is about to show. Every command first asks the watch its version, which a silent one
     * does not give; this one then reads a setting and shows nothing.
     */
    private fun answers(tool: Path, root: Path, platform: String, indicator: ProgressIndicator?): Boolean {
        val output = run(tool, root, ask(platform), indicator, ANSWER_MILLIS)
        return output.isCancelled || (output.exitCode == 0 && !output.isTimeout)
    }

    private fun ask(platform: String): List<String> =
        listOf("data-logging", "get-sends-enabled") + PebbleInstallCommand.connection(PebbleRunTarget.EMULATOR, platform, "", "")

    private fun run(tool: Path, root: Path, arguments: List<String>, indicator: ProgressIndicator?, timeoutMillis: Int) =
        CapturingProcessHandler(PebbleCommands.command(tool, arguments, root)).let { handler ->
            if (indicator != null) handler.runProcessWithProgressIndicator(indicator, timeoutMillis) else handler.runProcess(timeoutMillis)
        }

    private fun listening(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MILLIS) }
    }.isSuccess

    /** A cold start is some seconds; this is room for a slow machine, not an expected wait. */
    private const val WAIT_MILLIS = 60_000

    /** A watch that answers does in under two seconds; one that does not, never. */
    private const val ANSWER_MILLIS = 10_000
    private const val KILL_MILLIS = 20_000
    private const val POLL_MILLIS = 250L
    private const val CONNECT_MILLIS = 200

    private val LOG = logger<EmulatorWarmUp>()
}
