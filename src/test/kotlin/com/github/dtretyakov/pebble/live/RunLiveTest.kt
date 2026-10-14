package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.run.LogLevel
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunState
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.github.dtretyakov.pebble.sdk.PebbleTool
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A run as the run configuration starts it — on a pseudo-terminal — against the real emulator:
 * the app installs, and the watch's log follows.
 */
class RunLiveTest : IdeTestCase() {

    private companion object {
        /** `[22:41:53] process_manager.c:487> …`, which the firmware logs whenever an app starts. */
        val WATCH_LOG = Regex("""\[\d{2}:\d{2}:\d{2}] \S+:\d+>""")
    }

    fun `test the emulator run installs the app and streams its logs`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = PebbleTool.locate("", System.getenv("PATH")) ?: return
        val root = LiveSdk.fixture("fixture-c")
        check(LiveSdk.run(root, "build").exitCode == 0)
        // The emulator up first, so the run under test meets a running one, as a second run does.
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val arguments = PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "emery", "", "", logs = true)
        val handler = PebbleRunState.processHandler(tool, arguments, root)
        val output = StringBuffer()
        val logged = CountDownLatch(1)
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                    if (output.contains("App install succeeded.") && WATCH_LOG.containsMatchIn(output)) logged.countDown()
                }
            },
        )
        handler.startNotify()
        try {
            assertTrue("no log within the time limit:\n$output", logged.await(4, TimeUnit.MINUTES))
        } finally {
            handler.destroyProcess()
            handler.waitFor(10_000)
        }
    }

    fun `test only warnings and errors are shown when that is the minimum`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = PebbleTool.locate("", System.getenv("PATH")) ?: return
        val root = LiveSdk.fixture("fixture-c")
        java.nio.file.Files.writeString(
            root.resolve("src/c/fixture-c.c"),
            """
            #include <pebble.h>
            int main(void) {
              APP_LOG(APP_LOG_LEVEL_ERROR, "an error");
              APP_LOG(APP_LOG_LEVEL_WARNING, "a warning");
              APP_LOG(APP_LOG_LEVEL_INFO, "an info");
              APP_LOG(APP_LOG_LEVEL_DEBUG, "a debug");
              app_event_loop();
            }
            """.trimIndent(),
        )
        check(LiveSdk.run(root, "build").exitCode == 0)
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val arguments = PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "emery", "", "", logs = true)
        val handler = PebbleRunState.processHandler(tool, arguments, root, LogLevel.WARNING)
        val output = StringBuffer()
        val done = CountDownLatch(1)
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                    if (output.contains("a warning")) done.countDown()
                }
            },
        )
        handler.startNotify()
        try {
            assertTrue("no warning within the time limit:\n$output", done.await(4, TimeUnit.MINUTES))
            Thread.sleep(2000) // the info and debug lines follow the warning, if they are coming at all
            assertTrue(output.toString(), output.contains("an error"))
            assertFalse(output.toString(), output.contains("an info") || output.contains("a debug"))
            assertTrue(output.toString(), output.contains("App install succeeded."))
        } finally {
            handler.destroyProcess()
            handler.waitFor(10_000)
        }
    }
}
