package com.github.dtretyakov.pebble.emulator

import java.awt.image.BufferedImage

/**
 * The emulator's screen over time, as a GIF's frames: each frame the emulator sends, cropped to the
 * watch's screen, held until the next. QEMU sends a frame only when the screen changes, so a still
 * watchface costs one frame however long it is shown.
 *
 * Stops itself at [MAX_FRAMES] or [MAX_MILLIS], whichever comes first: a GIF is for showing a
 * moment, and an hour of one is a file nobody can open.
 */
class GifRecorder(private val clock: () -> Long = System::currentTimeMillis) {

    private val frames = mutableListOf<Pair<BufferedImage, Long>>()
    private var started = 0L

    @Volatile
    var recording = false
        private set

    @Synchronized
    fun start() {
        frames.clear()
        started = clock()
        recording = true
    }

    /** Adds the screen as it is now; true while still recording. */
    @Synchronized
    fun add(screen: BufferedImage): Boolean {
        if (!recording) return false
        val now = clock()
        frames += copy(screen) to now
        if (frames.size >= MAX_FRAMES || now - started >= MAX_MILLIS) recording = false
        return recording
    }

    /** The frames with how long each was shown — the last until now. Empty when nothing was seen. */
    @Synchronized
    fun stop(): List<GifWriter.Frame> {
        recording = false
        val end = clock()
        return frames.mapIndexed { index, (image, at) ->
            GifWriter.Frame(image, (frames.getOrNull(index + 1)?.second ?: end) - at)
        }.also { frames.clear() }
    }

    private fun copy(image: BufferedImage): BufferedImage =
        BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply { drawImage(image, 0, 0, null) }.dispose()
        }

    companion object {
        const val MAX_FRAMES = 1800
        const val MAX_MILLIS = 60_000L
    }
}
