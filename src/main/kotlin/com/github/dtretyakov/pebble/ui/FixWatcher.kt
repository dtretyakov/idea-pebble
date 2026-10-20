package com.github.dtretyakov.pebble.ui

import com.github.dtretyakov.pebble.project.EmulatorCheck
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.ToolHosts
import com.intellij.openapi.application.ApplicationManager
import com.intellij.util.concurrency.AppExecutorUtil
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Watches for a command run in the terminal to take effect, and then says so everywhere.
 *
 * The terminal runs in the IDE, so the IDE never loses focus and nothing else would notice that
 * `brew install libpng` has finished: the balloon would go on offering it, and Run would go on
 * refusing. Looks every few seconds, for as long as an install could reasonably take — once per
 * problem, however many of the banner, the balloon and the settings page it was started from.
 */
internal object FixWatcher {

    private val watching = ConcurrentHashMap<PebbleEnvironment.Concern, ScheduledFuture<*>>()

    fun watch(concern: PebbleEnvironment.Concern) {
        val started = System.currentTimeMillis()
        watching.compute(concern) { _, running ->
            running?.takeUnless { it.isDone } ?: AppExecutorUtil.getAppScheduledExecutorService().scheduleWithFixedDelay(
                { look(concern, started) },
                PERIOD_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS,
            )
        }
    }

    private fun look(concern: PebbleEnvironment.Concern, started: Long) {
        if (System.currentTimeMillis() - started > LIMIT_MILLIS) return stop(concern)
        // Under WSL what is installed is known by asking the distribution, which announces a change.
        ToolHosts.refreshNow()
        // The emulator's answer is a process away, and asking announces a change of it; the others
        // are files, read by the checklist itself.
        if (concern == PebbleEnvironment.Concern.EMULATOR) {
            EmulatorCheck.getInstance().checkNow(PebbleSdkService.getInstance().currentSdk())
        }
        val item = PebbleEnvironment.of(PebbleEnvironment.check(), concern)
        if (item != null && item.status != PebbleEnvironment.Status.READY) return
        stop(concern)
        if (concern != PebbleEnvironment.Concern.EMULATOR) {
            ApplicationManager.getApplication().messageBus.syncPublisher(PebbleEnvironment.TOPIC).changed()
        }
    }

    private fun stop(concern: PebbleEnvironment.Concern) {
        watching.remove(concern)?.cancel(false)
    }

    private const val PERIOD_SECONDS = 3L
    private const val LIMIT_MILLIS = 10 * 60 * 1000L
}
