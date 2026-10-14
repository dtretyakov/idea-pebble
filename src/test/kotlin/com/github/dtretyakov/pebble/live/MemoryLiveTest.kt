package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.build.MemoryReportParser
import com.github.dtretyakov.pebble.run.HeapLeakHint
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunState
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/** Memory as pebble-tool and the firmware report it: per platform after a build, and a leak at exit. */
class MemoryLiveTest : IdeTestCase() {

    fun `test a build reports every platform's memory`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c")
        val output = LiveSdk.run(root, "build")
        assertEquals(output.text, 0, output.exitCode)
        val parser = MemoryReportParser()
        val usages = output.text.lines().mapNotNull(parser::parseLine)
        assertEquals(output.text, listOf("aplite", "basalt", "chalk", "diorite", "emery", "flint", "gabbro"), usages.map { it.platform }.sorted())
        assertTrue(usages.all { it.ramLimit != null && it.heapFree != null && it.resourceLimit != null })
    }

    fun `test an app that exits with memory allocated is said to leak`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c")
        root.resolve("src/c/fixture-c.c").writeText(
            """
            #include <pebble.h>

            static char *s_leak;

            int main(void) {
              // Used, or the compiler drops the allocation.
              s_leak = malloc(100);
              snprintf(s_leak, 100, "leaking %d", 100);
              APP_LOG(APP_LOG_LEVEL_INFO, "%s", s_leak);
              app_event_loop();
            }
            """.trimIndent(),
        )
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val handler = PebbleRunState.processHandler(tool, PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "emery", "", "", logs = true), root)
        HeapLeakHint.attach(handler)
        val output = StringBuffer()
        val running = CountDownLatch(1)
        val leak = CountDownLatch(1)
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                    if ("leaking" in output) running.countDown()
                    if (outputType == ProcessOutputTypes.SYSTEM && "still allocated" in event.text) leak.countDown()
                }
            },
        )
        handler.startNotify()
        try {
            assertTrue("the app never ran:\n$output", running.await(2, TimeUnit.MINUTES))
            // Installing it again ends the running one, which is when the firmware reports its heap.
            LiveSdk.install(root, "--emulator", "emery", "--vnc")
            assertTrue("no word of the leak:\n$output", leak.await(30, TimeUnit.SECONDS))
            // The hundred bytes and the allocator's four of its own.
            assertTrue(output.toString(), "Fixture C exited with 104 bytes still allocated" in output)
        } finally {
            handler.destroyProcess()
            handler.waitFor(10_000)
        }
    }
}
