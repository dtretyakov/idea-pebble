package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode

class GifRecordingTest {

    private fun screen(colour: Color) = BufferedImage(200, 228, BufferedImage.TYPE_INT_RGB).also {
        it.createGraphics().apply { color = colour; fillRect(0, 0, 200, 228) }.dispose()
    }

    @Test
    fun `each frame is held until the next, the last until recording stops`() {
        var now = 1000L
        val recorder = GifRecorder { now }
        recorder.start()
        recorder.add(screen(Color.RED))
        now = 1250
        recorder.add(screen(Color.GREEN))
        now = 2000
        assertEquals(listOf(250L, 750L), recorder.stop().map { it.millis })
        assertFalse(recorder.recording)
    }

    @Test
    fun `recording stops itself at the time limit`() {
        var now = 0L
        val recorder = GifRecorder { now }
        recorder.start()
        recorder.add(screen(Color.RED))
        now = GifRecorder.MAX_MILLIS
        assertFalse(recorder.add(screen(Color.BLUE)))
        assertFalse(recorder.recording)
    }

    @Test
    fun `the GIF has every frame with its delay, and loops`() {
        val bytes = ByteArrayOutputStream().also {
            GifWriter.write(
                listOf(GifWriter.Frame(screen(Color.RED), 250), GifWriter.Frame(screen(Color.GREEN), 1000), GifWriter.Frame(screen(Color.BLUE), 5)),
                it,
            )
        }.toByteArray()

        val reader = ImageIO.getImageReadersByFormatName("gif").next()
        reader.input = ImageIO.createImageInputStream(ByteArrayInputStream(bytes))
        assertEquals(3, reader.getNumImages(true))
        val delays = (0 until 3).map { index ->
            val root = reader.getImageMetadata(index).getAsTree("javax_imageio_gif_image_1.0") as IIOMetadataNode
            (root.getElementsByTagName("GraphicControlExtension").item(0) as IIOMetadataNode).getAttribute("delayTime")
        }
        assertEquals(listOf("25", "100", "2"), delays)
        assertEquals(Color.GREEN.rgb, reader.read(1).getRGB(10, 10))
        assertEquals(200, reader.read(0).width)
    }
}
