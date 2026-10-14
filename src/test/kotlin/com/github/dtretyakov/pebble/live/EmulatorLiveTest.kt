package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.EmulatorScreen
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
}
