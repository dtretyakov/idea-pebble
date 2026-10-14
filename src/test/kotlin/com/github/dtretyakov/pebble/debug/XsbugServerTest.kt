package com.github.dtretyakov.pebble.debug

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutionException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** The server against a stand-in for pebble-tool's bridge, on a port of the test's own. */
class XsbugServerTest {

    private val events = LinkedBlockingQueue<String>()
    private val messages = LinkedBlockingQueue<XsbugMessage>()
    private var answer: (XsbugConnection) -> Unit = { it.send(XsbugCommands.setAllBreakpoints(emptyList())) }

    private val server = XsbugServer.bind(
        port = 0,
        listener = object : XsbugServer.Listener {
            override fun connected(connection: XsbugConnection, login: XsbugLogin) {
                answer(connection)
                events += "connected ${login.name}"
            }

            override fun message(connection: XsbugConnection, message: XsbugMessage) {
                messages += message
            }

            override fun disconnected(connection: XsbugConnection) {
                events += "disconnected"
            }
        },
    )

    @AfterEach
    fun close() = server.close()

    private fun bridge() = Socket(InetAddress.getLoopbackAddress(), server.port).apply { soTimeout = 5000 }

    private fun Socket.write(text: String) = getOutputStream().apply { write(text.toByteArray()); flush() }

    private fun InputStream.readUntil(end: String): String {
        val out = StringBuilder()
        while (!out.endsWith(end)) {
            val c = read()
            if (c < 0) break
            out.append(c.toChar())
        }
        return out.toString()
    }

    private val login = "\r\n<xsbug><login name=\"main\" value=\"XS 17.8.0 32-bit LE 16-bit ID\" flags=\"e\"/></xsbug>\r\n"

    @Test
    fun `pebble-tool's checks of the port are let go, and the login is answered at once`() {
        repeat(2) { bridge().close() }
        bridge().use { watch ->
            val started = System.nanoTime()
            watch.write(login)
            val reply = watch.getInputStream().readUntil("</set-all-breakpoints>\r\n")
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500)
            assertEquals("\r\n<set-all-breakpoints></set-all-breakpoints>\r\n", reply)
            assertEquals("connected main", events.poll(5, TimeUnit.SECONDS))
            assertTrue(events.isEmpty(), "the checks are not runs: $events")
        }
        assertEquals("disconnected", events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a view answers the request before it, and what comes unasked goes to the listener`() {
        bridge().use { watch ->
            watch.write(login)
            watch.getInputStream().readUntil("</set-all-breakpoints>\r\n")
            events.poll(5, TimeUnit.SECONDS)
            val connection = server.connection!!

            val reply = connection.request(XsbugCommands.select("@2002AC34"))
            assertEquals("\r\n<select id=\"@2002AC34\"/>\r\n", watch.getInputStream().readUntil("/>\r\n"))
            watch.write("\r\n<xsbug><samples>1,2,3</samples></xsbug>\r\n\r\n<xsbug><log>tick</log></xsbug>\r\n")
            watch.write("\r\n<xsbug><local name=\"draw\" value=\"@2002AC34\"><property flags=\" cEw_\" name=\"white\" value=\"255\"/></local><global/></xsbug>\r\n")

            val view = reply.get(5, TimeUnit.SECONDS)
            assertEquals("white", view.local!!.properties.single().name)
            val unasked = listOf(messages.poll(5, TimeUnit.SECONDS), messages.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf("tick"), unasked.flatMap { it.logs }.map { it.text })
        }
    }

    @Test
    fun `a request still waiting when the app goes fails`() {
        val watch = bridge()
        watch.write(login)
        watch.getInputStream().readUntil("</set-all-breakpoints>\r\n")
        events.poll(5, TimeUnit.SECONDS)
        val reply = server.connection!!.request(XsbugCommands.toggle("@1"))
        watch.close()
        assertThrows<ExecutionException> { reply.get(5, TimeUnit.SECONDS) }
        assertEquals("disconnected", events.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun `logging out waits for the bridge to hang up`() {
        bridge().use { watch ->
            watch.write(login)
            val input = watch.getInputStream()
            input.readUntil("</set-all-breakpoints>\r\n")
            events.poll(5, TimeUnit.SECONDS)
            val connection = server.connection!!

            Thread {
                input.readUntil("<logout/>\r\n")
                watch.close()
            }.start()
            assertTrue(connection.logoutAndAwaitClose(5000))
            assertFalse(connection.isOpen)
        }
    }

    @Test
    fun `logging out gives up when nobody hangs up`() {
        bridge().use { watch ->
            watch.write(login)
            watch.getInputStream().readUntil("</set-all-breakpoints>\r\n")
            events.poll(5, TimeUnit.SECONDS)
            assertFalse(server.connection!!.logoutAndAwaitClose(300))
        }
    }

    @Test
    fun `a new run replaces the one before`() {
        val first = bridge()
        first.write(login)
        first.getInputStream().readUntil("</set-all-breakpoints>\r\n")
        val firstConnection = server.connection!!
        bridge().use { second ->
            second.write(login)
            second.getInputStream().readUntil("</set-all-breakpoints>\r\n")
            assertTrue(server.connection !== firstConnection)
        }
        first.close()
    }

    @Test
    fun `a port already listened on is refused`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { taken ->
            assertThrows<XsbugServer.PortInUse> {
                XsbugServer.bind(taken.localPort, object : XsbugServer.Listener {
                    override fun connected(connection: XsbugConnection, login: XsbugLogin) = Unit
                    override fun message(connection: XsbugConnection, message: XsbugMessage) = Unit
                    override fun disconnected(connection: XsbugConnection) = Unit
                })
            }
        }
    }
}
