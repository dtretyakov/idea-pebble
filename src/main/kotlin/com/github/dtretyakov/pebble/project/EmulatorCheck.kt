package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.EmulatorStart
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether the active SDK's emulator starts here, asked once per emulator binary and remembered.
 *
 * Asking starts a process, and the environment checklist promises to start none, so the checklist
 * reads only what was found here. Asked at the end of Set Up, when a Pebble project opens and when
 * the settings page opens; asked again, at most every few seconds, while the answer is a failure —
 * the user may have just installed what was missing, and the binary is the same either way.
 */
@Service(Service.Level.APP)
class EmulatorCheck {

    private data class Stamp(val qemu: Path, val modified: Long)

    private class Answer(val verdict: EmulatorStart.Verdict, val at: Long)

    private val answers = ConcurrentHashMap<Stamp, Answer>()

    /** What is known about [sdk]'s emulator, or null until it has been asked. */
    fun cached(sdk: PebbleSdk?): EmulatorStart.Verdict? = sdk?.let(::stampOf)?.let(answers::get)?.verdict

    /** Binaries being asked right now, so callers in the meantime do not start a second process. */
    private val asking: MutableSet<Stamp> = ConcurrentHashMap.newKeySet()

    /**
     * Asks [sdk]'s emulator now, on the calling thread — never the UI thread — and tells
     * [PebbleEnvironment.TOPIC] when the answer is not the one it had: a watch on a failing check
     * asks every few seconds, and each announcement has every open project read the machine again.
     * Null when there is no emulator to ask.
     */
    fun checkNow(sdk: PebbleSdk?): EmulatorStart.Verdict? {
        val stamp = sdk?.let(::stampOf) ?: return null
        val output = runCatching {
            CapturingProcessHandler(GeneralCommandLine(stamp.qemu.toString(), "--version")).runProcess(TIMEOUT_MILLIS)
        }.getOrNull() ?: return null
        // A timeout means the loader got as far as running it, which is what is being asked.
        val verdict = if (output.isTimeout) EmulatorStart.Verdict.Starts else EmulatorStart.read(output.exitCode, output.stdout + "\n" + output.stderr)
        val before = answers.put(stamp, Answer(verdict, System.currentTimeMillis()))?.verdict
        if (verdict != before) ApplicationManager.getApplication().messageBus.syncPublisher(PebbleEnvironment.TOPIC).changed()
        return verdict
    }

    /**
     * Asks in the background when there is no answer yet, or when the answer is a failure older
     * than a few seconds. Cheap to call often: the run configuration calls it on every check.
     */
    fun refresh(sdk: PebbleSdk?) {
        val stamp = sdk?.let(::stampOf) ?: return
        val answer = answers[stamp]
        val stale = answer == null ||
            (answer.verdict != EmulatorStart.Verdict.Starts && System.currentTimeMillis() - answer.at > RECHECK_MILLIS)
        if (!stale || !asking.add(stamp)) return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                checkNow(sdk)
            } finally {
                asking.remove(stamp)
            }
        }
    }

    private fun stampOf(sdk: PebbleSdk): Stamp? {
        val qemu = sdk.toolchain.resolve("bin/qemu-pebble")
        val modified = runCatching { Files.getLastModifiedTime(qemu).toMillis() }.getOrNull() ?: return null
        return Stamp(qemu, modified)
    }

    companion object {
        private const val TIMEOUT_MILLIS = 10_000
        private const val RECHECK_MILLIS = 5_000L

        fun getInstance(): EmulatorCheck = service()
    }
}
