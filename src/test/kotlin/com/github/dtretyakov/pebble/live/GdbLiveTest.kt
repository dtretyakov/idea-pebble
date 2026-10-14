package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.EmulatorSession
import com.github.dtretyakov.pebble.emulator.RfbClient
import com.github.dtretyakov.pebble.emulator.RunningEmulator
import com.github.dtretyakov.pebble.run.GdbSession
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.awt.image.BufferedImage
import java.nio.file.Files

/**
 * A C debug session as Debug starts it: the debug build installed, gdb attached in the console,
 * a breakpoint on a click handler, the button pressed through the emulator's screen, and gdb
 * stopped in the handler with a backtrace to show for it.
 */
class GdbLiveTest : IdeTestCase() {

    fun `test gdb stops in a click handler when the button is pressed`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c")
        Files.writeString(
            root.resolve("src/c/fixture-c.c"),
            """
            #include <pebble.h>
            static Window *s_window;
            static void select_clicked(ClickRecognizerRef recognizer, void *context) {
              APP_LOG(APP_LOG_LEVEL_INFO, "select");
            }
            static void click_config(void *context) {
              window_single_click_subscribe(BUTTON_ID_SELECT, select_clicked);
            }
            int main(void) {
              s_window = window_create();
              window_set_click_config_provider(s_window, click_config);
              window_stack_push(s_window, false);
              app_event_loop();
            }
            """.trimIndent(),
        )
        check(LiveSdk.run(root, "build", "--debug").exitCode == 0)
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val handler = KillableColoredProcessHandler(GdbSession.commandLine(tool, "emery", root))
        val output = StringBuffer()
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            },
        )
        handler.startNotify()
        val session = EmulatorSession.getInstance(project)
        session.addListener(
            object : EmulatorSession.Listener {
                override fun frame(emulator: RunningEmulator, image: BufferedImage) = Unit
                override fun idle() = Unit
            },
            testRootDisposable,
        )
        session.startWatching()
        try {
            waitFor(output, "(gdb)")
            type(handler, "break select_clicked")
            waitFor(output, "Breakpoint 2 at")
            type(handler, "continue")
            waitFor(output, "Continuing.")
            Thread.sleep(1000)
            val deadline = System.currentTimeMillis() + 30_000
            while (session.client == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
            session.client!!.key(RfbClient.KEY_RIGHT, true)
            Thread.sleep(150)
            session.client!!.key(RfbClient.KEY_RIGHT, false)
            waitFor(output, "select_clicked")
            type(handler, "bt")
            waitFor(output, "#0")
            assertTrue(output.toString(), Regex("""#0\s+.*select_clicked""").containsMatchIn(output.replace(Regex("\u001b\\[[0-9;?]*[a-zA-Z]"), "")))
            type(handler, "detach")
        } finally {
            session.stopWatching()
            handler.destroyProcess()
            handler.waitFor(10_000)
        }
    }

    private fun type(handler: KillableColoredProcessHandler, line: String) {
        handler.processInput.apply {
            write((line + "\n").toByteArray())
            flush()
        }
    }

    private fun waitFor(output: StringBuffer, text: String) {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            if (text in output) return
            Thread.sleep(200)
        }
        throw AssertionError("no '$text' in:\n$output")
    }
}
