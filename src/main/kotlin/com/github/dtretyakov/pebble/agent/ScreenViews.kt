package com.github.dtretyakov.pebble.agent

import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.createDirectories
import kotlin.math.ceil
import kotlin.math.max

/**
 * Screens as an agent looks at them. A watch's screen is 144 to 260 pixels across, and at that
 * size a model misreads its text; the same pixels, each drawn as a square block — never blurred,
 * so what the watch drew is what is seen — are read as well as a phone's. A UI test's failure is
 * one picture: the reference, the screen, and where they differ, side by side and labelled.
 */
object ScreenViews {

    /** About this many pixels along the longer side. */
    const val SIZE = 640

    fun scale(width: Int, height: Int): Int = max(1, ceil(SIZE.toDouble() / max(width, height)).toInt())

    fun enlarge(image: BufferedImage, scale: Int = scale(image.width, image.height)): BufferedImage {
        val large = BufferedImage(image.width * scale, image.height * scale, BufferedImage.TYPE_INT_RGB)
        val graphics = large.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.drawImage(image, 0, 0, large.width, large.height, null)
        graphics.dispose()
        return large
    }

    /** Panels of the same scale side by side, each with its caption above it. */
    fun sideBySide(panels: List<Pair<String, BufferedImage>>): BufferedImage {
        val widest = panels.maxOf { max(it.second.width, it.second.height) }
        val scale = max(1, ceil(SIZE.toDouble() / 2 / widest).toInt())
        val gap = 16
        val caption = 28
        val enlarged = panels.map { it.first to enlarge(it.second, scale) }
        val width = enlarged.sumOf { it.second.width } + gap * (enlarged.size + 1)
        val height = enlarged.maxOf { it.second.height } + caption + gap * 2
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0x30, 0x30, 0x30)
        graphics.fillRect(0, 0, width, height)
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        var x = gap
        for ((label, panel) in enlarged) {
            graphics.color = Color.WHITE
            graphics.drawString(label, x, gap + 18)
            graphics.drawImage(panel, x, gap + caption, null)
            x += panel.width + gap
        }
        graphics.dispose()
        return image
    }

    fun write(image: BufferedImage, file: Path): Path {
        file.parent.createDirectories()
        ImageIO.write(image, "png", file.toFile())
        return file
    }
}
