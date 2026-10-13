package com.github.dtretyakov.pebble.emulator

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.concurrency.AppExecutorUtil
import java.awt.image.BufferedImage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * The IDE's connection to the emulator's screen.
 *
 * pebble-tool starts emulators as it pleases — from a run here, or from a terminal — so rather than
 * be told, this looks: every couple of seconds, while someone is watching, it reads pebble-tool's
 * own record of running emulators and connects to the one with a VNC server. Watching stops when
 * the tool window is hidden, so a closed panel costs nothing.
 */
@Service(Service.Level.PROJECT)
class EmulatorSession : Disposable {

    interface Listener {
        /** A frame from [emulator]; [image] is the whole framebuffer. Called on a background thread. */
        fun frame(emulator: RunningEmulator, image: BufferedImage)

        /** No emulator to show: none running, or none with a screen to share. */
        fun idle()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()

    @Volatile
    private var connection: Pair<RunningEmulator, RfbClient>? = null

    @Volatile
    private var watching: ScheduledFuture<*>? = null

    /** The emulator on screen now, for the buttons and the screenshot. */
    val emulator: RunningEmulator? get() = connection?.first

    val client: RfbClient? get() = connection?.second

    @Volatile
    var lastFrame: BufferedImage? = null
        private set

    fun addListener(listener: Listener, parent: Disposable) {
        listeners += listener
        com.intellij.openapi.util.Disposer.register(parent) { listeners -= listener }
    }

    /** Starts looking for an emulator, and keeps looking until [stopWatching]. */
    @Synchronized
    fun startWatching() {
        if (watching != null) return
        watching = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay(::look, 0, POLL_SECONDS, TimeUnit.SECONDS)
    }

    @Synchronized
    fun stopWatching() {
        watching?.cancel(false)
        watching = null
        disconnect()
    }

    /** Looks now rather than at the next tick: a run has just started one. */
    fun lookNow() {
        if (watching != null) AppExecutorUtil.getAppExecutorService().execute(::look)
    }

    @Synchronized
    private fun look() {
        val running = EmulatorState.withScreen()
        val current = connection
        when {
            running == null -> if (current != null) disconnect() else listeners.forEach { it.idle() }
            current == null || current.first != running -> {
                disconnect()
                connect(running)
            }
        }
    }

    private fun connect(emulator: RunningEmulator) {
        val client = RfbClient("127.0.0.1", EmulatorState.VNC_PORT, object : RfbClient.Listener {
            override fun frame(image: BufferedImage) {
                lastFrame = image
                listeners.forEach { it.frame(emulator, image) }
            }

            override fun disconnected(cause: Throwable?) {
                synchronized(this@EmulatorSession) {
                    if (connection?.first == emulator) connection = null
                }
                lastFrame = null
                listeners.forEach { it.idle() }
            }
        })
        connection = emulator to client
        client.start()
    }

    private fun disconnect() {
        connection?.second?.close()
        connection = null
    }

    override fun dispose() = stopWatching()

    companion object {
        private const val POLL_SECONDS = 2L

        fun getInstance(project: Project): EmulatorSession = project.service()
    }
}
