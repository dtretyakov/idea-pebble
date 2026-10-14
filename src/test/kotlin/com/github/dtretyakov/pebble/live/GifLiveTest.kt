package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.emulator.EmulatorScreen
import com.github.dtretyakov.pebble.emulator.EmulatorSession
import com.github.dtretyakov.pebble.emulator.GifRecorder
import com.github.dtretyakov.pebble.emulator.GifWriter
import com.github.dtretyakov.pebble.emulator.RfbClient
import com.github.dtretyakov.pebble.emulator.RunningEmulator
import com.github.dtretyakov.pebble.testing.IdeTestCase
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/** A recording of the real emulator while a button is pressed: more than one frame, the watch's size. */
class GifLiveTest : IdeTestCase() {

    fun `test a recording of the emulator becomes a GIF`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val root = LiveSdk.fixture("fixture-c")
        LiveSdk.run(root, "build").let { assertEquals(it.text, 0, it.exitCode) }
        LiveSdk.install(root, "--emulator", "emery", "--vnc").let { assertEquals(it.text, 0, it.exitCode) }

        val recorder = GifRecorder()
        val firstFrame = CountDownLatch(1)
        val session = EmulatorSession.getInstance(project)
        session.addListener(
            object : EmulatorSession.Listener {
                override fun frame(emulator: RunningEmulator, image: BufferedImage) {
                    val area = EmulatorScreen.of(emulator.platform, image.width, image.height)
                    recorder.add(image.getSubimage(area.x, area.y, area.width, area.height))
                    firstFrame.countDown()
                }

                override fun idle() = Unit
            },
            testRootDisposable,
        )
        recorder.start()
        session.startWatching()
        try {
            assertTrue(firstFrame.await(30, TimeUnit.SECONDS))
            Thread.sleep(500)
            session.client!!.key(RfbClient.KEY_DOWN, true)
            session.client!!.key(RfbClient.KEY_DOWN, false)
            Thread.sleep(1500)
            session.client!!.key(RfbClient.KEY_LEFT, true)
            session.client!!.key(RfbClient.KEY_LEFT, false)
            Thread.sleep(1000)
        } finally {
            session.stopWatching()
        }

        val frames = recorder.stop()
        assertTrue("only ${frames.size} frames", frames.size >= 2)
        val bytes = ByteArrayOutputStream().also { GifWriter.write(frames, it) }.toByteArray()
        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(frames.size, reader.getNumImages(true))
        assertEquals(200, reader.read(0).width)
        assertEquals(228, reader.read(0).height)
    }
}
