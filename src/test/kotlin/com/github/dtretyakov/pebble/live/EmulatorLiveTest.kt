package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.EmulatorScreen
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleRunState
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.nio.file.Files
import com.github.dtretyakov.pebble.emulator.EmulatorSession
import com.github.dtretyakov.pebble.emulator.RfbClient
import com.github.dtretyakov.pebble.emulator.RunningEmulator
import com.github.dtretyakov.pebble.testing.IdeTestCase
import java.awt.image.BufferedImage
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The emulator's screen as the tool window sees it: the session finds the emulator pebble-tool
 * started, frames arrive, the watch's screen is where the crop says, and a button press reaches
 * the watch and changes what it shows.
 */
class EmulatorLiveTest : IdeTestCase() {

    fun `test the session shows the emulator and its buttons work`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val root = LiveSdk.fixture("fixture-c")
        check(LiveSdk.run(root, "build").exitCode == 0)
        val install = LiveSdk.install(root, "--emulator", "emery", "--vnc")
        assertEquals(install.text, 0, install.exitCode)

        val frames = LinkedBlockingQueue<Pair<RunningEmulator, IntArray>>()
        val session = EmulatorSession.getInstance(project)
        session.addListener(
            object : EmulatorSession.Listener {
                override fun frame(emulator: RunningEmulator, image: BufferedImage) {
                    val area = EmulatorScreen.of(emulator.platform, image.width, image.height)
                    frames.put(emulator to image.getRGB(area.x, area.y, area.width, area.height, null, 0, area.width))
                }

                override fun idle() = Unit
            },
            testRootDisposable,
        )
        session.startWatching()
        try {
            val (emulator, first) = generateSequence { frames.poll(30, TimeUnit.SECONDS) }
                .first { (_, pixels) -> pixels.any { it and 0xFFFFFF != 0 } }
            assertEquals("emery", emulator.platform)
            assertEquals(200 * 228, first.size)

            session.client!!.key(RfbClient.KEY_DOWN, true)
            Thread.sleep(150)
            session.client!!.key(RfbClient.KEY_DOWN, false)
            val changed = generateSequence { frames.poll(10, TimeUnit.SECONDS) }.take(20)
                .any { (_, pixels) -> !pixels.contentEquals(first) }
            assertTrue("pressing Down changed nothing on the screen", changed)

            session.client!!.key(RfbClient.KEY_LEFT, true)
            session.client!!.key(RfbClient.KEY_LEFT, false)
        } finally {
            session.stopWatching()
        }
    }

    /** A click on the screen is a touch where it was clicked: an app subscribed to touches says where. */
    fun `test a pointer press is a touch at the same place`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c")
        Files.writeString(
            root.resolve("src/c/fixture-c.c"),
            """
            #include <pebble.h>
            static void touched(const TouchEvent *event, void *context) {
              APP_LOG(APP_LOG_LEVEL_INFO, "touch %d at %d,%d", (int)event->type, event->x, event->y);
            }
            int main(void) {
              Window *window = window_create();
              window_stack_push(window, false);
              touch_service_subscribe(touched, NULL);
              APP_LOG(APP_LOG_LEVEL_INFO, "touch ready");
              app_event_loop();
            }
            """.trimIndent(),
        )
        check(LiveSdk.run(root, "build").exitCode == 0)
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        val logs = PebbleRunState.processHandler(
            tool, PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "emery", "", "", logs = true), root,
        )
        val output = StringBuffer()
        logs.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            },
        )
        logs.startNotify()
        val session = EmulatorSession.getInstance(project)
        val frames = LinkedBlockingQueue<BufferedImage>()
        session.addListener(
            object : EmulatorSession.Listener {
                override fun frame(emulator: RunningEmulator, image: BufferedImage) = frames.put(image)
                override fun idle() = Unit
            },
            testRootDisposable,
        )
        session.startWatching()
        try {
            waitFor(output, "touch ready")
            // A touch in the first moments after the app logs it can lose its Touchdown while the
            // app is still being brought to the front; a person is never that quick.
            Thread.sleep(1500)
            val image = frames.poll(30, TimeUnit.SECONDS)!!
            val screen = EmulatorScreen.of("emery", image.width, image.height)
            val pointer = EmulatorScreen.pointerFor(screen, image.width, image.height, 100, 114)
            session.client!!.pointer(pointer.x, pointer.y, true)
            Thread.sleep(200)
            session.client!!.pointer(pointer.x, pointer.y, false)

            val touch = waitFor(output, Regex("""touch 0 at (\d+),(\d+)"""))
            val (x, y) = touch.destructured
            assertTrue("touched at $x,$y", kotlin.math.abs(x.toInt() - 100) <= 1 && kotlin.math.abs(y.toInt() - 114) <= 1)
            waitFor(output, Regex("""touch 1 at"""))
        } finally {
            session.stopWatching()
            logs.destroyProcess()
            logs.waitFor(10_000)
        }
    }

    private fun waitFor(output: StringBuffer, text: String) = waitFor(output, Regex(Regex.escape(text)))

    private fun waitFor(output: StringBuffer, pattern: Regex): MatchResult {
        val deadline = System.currentTimeMillis() + 120_000
        while (System.currentTimeMillis() < deadline) {
            pattern.find(output)?.let { return it }
            Thread.sleep(200)
        }
        throw AssertionError("no '$pattern' in:\n$output")
    }
}
