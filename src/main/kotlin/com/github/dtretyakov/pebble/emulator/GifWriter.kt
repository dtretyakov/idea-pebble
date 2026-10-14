package com.github.dtretyakov.pebble.emulator

import java.awt.image.BufferedImage
import java.io.OutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadataNode

/**
 * An animated GIF from frames and how long each was on screen, with the JDK's own GIF writer —
 * no dependency. A Pebble screen has at most 64 colours, so the GIF palette loses nothing.
 */
object GifWriter {

    class Frame(val image: BufferedImage, val millis: Long)

    fun write(frames: List<Frame>, output: OutputStream) {
        require(frames.isNotEmpty()) { "a GIF needs at least one frame" }
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(output).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            frames.forEachIndexed { index, frame ->
                val type = ImageTypeSpecifier.createFromRenderedImage(frame.image)
                val metadata = writer.getDefaultImageMetadata(type, writer.defaultWriteParam)
                val format = metadata.nativeMetadataFormatName
                val root = metadata.getAsTree(format) as IIOMetadataNode
                root.child("GraphicControlExtension").apply {
                    setAttribute("disposalMethod", "none")
                    setAttribute("userInputFlag", "FALSE")
                    setAttribute("transparentColorFlag", "FALSE")
                    // Hundredths of a second, at least 2: browsers treat less as "as fast as you can".
                    setAttribute("delayTime", (frame.millis / 10).coerceIn(2, 65535).toString())
                    setAttribute("transparentColorIndex", "0")
                }
                if (index == 0) {
                    root.child("ApplicationExtensions").appendChild(
                        IIOMetadataNode("ApplicationExtension").apply {
                            setAttribute("applicationID", "NETSCAPE")
                            setAttribute("authenticationCode", "2.0")
                            userObject = byteArrayOf(1, 0, 0) // loop forever
                        },
                    )
                }
                metadata.setFromTree(format, root)
                writer.writeToSequence(IIOImage(frame.image, null, metadata), writer.defaultWriteParam)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
    }

    private fun IIOMetadataNode.child(name: String): IIOMetadataNode {
        for (i in 0 until length) {
            val node = item(i)
            if (node.nodeName == name) return node as IIOMetadataNode
        }
        return IIOMetadataNode(name).also(::appendChild)
    }
}
