package com.github.dtretyakov.pebble.run

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key

/**
 * One sentence after pebble-tool's own error when a run could not reach the watch, saying where to
 * look.
 *
 * pebble-tool reports the socket's complaint — `[Errno 111] Connection refused` — which is true and
 * says nothing about the Pebble app's Developer Connection switch, the usual cause. Only added when
 * the run failed and its output reads like a connection failure, so it never sits under an error
 * it does not explain.
 */
object ConnectionHint {

    fun attach(handler: ProcessHandler, target: PebbleRunTarget, phoneAddress: String) {
        handler.addProcessListener(
            object : ProcessListener {
                private val output = StringBuilder()

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (output.length < LIMIT) output.append(event.text)
                }

                override fun processTerminated(event: ProcessEvent) {
                    if (event.exitCode == 0) return
                    hint(target, phoneAddress, output.toString())?.let {
                        handler.notifyTextAvailable("\n$it\n", ProcessOutputTypes.SYSTEM)
                    }
                }
            },
        )
    }

    fun hint(target: PebbleRunTarget, phoneAddress: String, output: String): String? {
        val text = output.lowercase()
        if (target == PebbleRunTarget.CLOUDPEBBLE && "login" in text) {
            return "CloudPebble needs you signed in: run `pebble login` in a terminal once, then run again."
        }
        if (CONNECTION_FAILURES.none { it in text }) return null
        return when (target) {
            PebbleRunTarget.PHONE ->
                "Could not reach the phone at ${phoneAddress.ifBlank { "the address given" }}. In the Pebble app, " +
                    "turn on Developer Connection and check the address it shows; the phone and this computer " +
                    "must be on the same network."
            PebbleRunTarget.CLOUDPEBBLE ->
                "Could not reach the phone through CloudPebble. In the Pebble app, turn on Developer Connection " +
                    "and its CloudPebble option."
            PebbleRunTarget.ADB ->
                "Could not reach the phone over adb. Check `adb devices` lists it, and that the Pebble app is open on it."
            // pebble-tool gives a starting emulator about five seconds, and a cold start on a slow
            // machine takes longer; the emulator is up by the time anyone reads this.
            PebbleRunTarget.EMULATOR ->
                "The emulator was still starting: pebble-tool waits only a few seconds for it. Run again. " +
                    "If it keeps failing, stop it from the Pebble Emulator tool window first."
        }
    }

    private val CONNECTION_FAILURES = listOf(
        "connection refused", "timed out", "timeout", "no route to host", "network is unreachable",
        "name or service not known", "could not connect", "failed to connect", "connection reset",
    )

    private const val LIMIT = 64 * 1024
}
