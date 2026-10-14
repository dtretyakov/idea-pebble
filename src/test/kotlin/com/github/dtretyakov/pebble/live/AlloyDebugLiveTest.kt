package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.run.AlloyDebugHint
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunState
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What Debug does for Alloy, as a run starts it: a debug build is installed with xsbug launched
 * beside it, and the app runs. On gabbro: a debug build left behind holds a "not responding"
 * dialog over the next app, and the other live tests use emery. Without a display xsbug cannot open, and pebble-tool goes on
 * without it — which is what is checked here; with one, xsbug comes up.
 */
class AlloyDebugLiveTest : IdeTestCase() {

    fun `test a debug build of an Alloy app launches xsbug and still runs`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-alloy")
        check(LiveSdk.run(root, "build", "--debug").exitCode == 0)
        // gabbro up first, with an ordinary app, past pebble-tool's few seconds' wait for a cold one.
        val warm = LiveSdk.fixture("fixture-c")
        check(LiveSdk.run(warm, "build").exitCode == 0)
        LiveSdk.install(warm, "--emulator", "gabbro", "--vnc")
        // Started cold if need be, with the ordinary pebble-tool wait: no install beforehand, since
        // with a debug build `pebble install` without `--logs` holds the bridge to xsbug open.

        val handler = PebbleRunState.processHandler(
            tool, PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "gabbro", "", "", logs = true), root,
        )
        AlloyDebugHint.attach(handler)
        val output = StringBuffer()
        val running = CountDownLatch(1)
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                    if ("Hello, Watchface." in output) running.countDown()
                }
            },
        )
        handler.startNotify()
        try {
            assertTrue("the app never ran:\n$output", running.await(4, TimeUnit.MINUTES))
            assertTrue(output.toString(), "Launching xsbug" in output)
        } finally {
            handler.destroyProcess()
            handler.waitFor(10_000)
        }
        assertTrue("no word on what follows the session:\n$output", "Press Back to dismiss" in output)
    }
}
