package com.github.dtretyakov.pebble.debug

import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The debugger's end of pebble-tool's bridge: a server on port 5002, where xsbug would listen.
 *
 * `pebble install` of an Alloy debug build launches xsbug only when nothing listens on
 * `localhost:5002`. With this server there, it connects to it instead and relays the watch's
 * debugger traffic both ways, untouched. Before that it checks the port twice by connecting and
 * hanging up at once; those connections never send anything and are let go here.
 *
 * Each connection that logs in is one run of the app, and replaces the one before. The listener
 * hears of it on the connection's reading thread and must answer the login there and then: the
 * engine gives up on a debugger that has not answered within two seconds.
 */
class XsbugServer private constructor(private val socket: ServerSocket, private val listener: Listener) : AutoCloseable {

    interface Listener {
        /** A run of the app has logged in; answer with the breakpoints now. */
        fun connected(connection: XsbugConnection, login: XsbugLogin)

        /** A message that is not a reply: a break, logs, the engine's instruments. */
        fun message(connection: XsbugConnection, message: XsbugMessage)

        /** The run has gone: the app ended, or the debugger logged out. */
        fun disconnected(connection: XsbugConnection)
    }

    val port: Int get() = socket.localPort

    @Volatile
    var connection: XsbugConnection? = null
        private set

    @Volatile
    private var closed = false

    private fun start() {
        Thread({ accept() }, "Alloy debugger").apply { isDaemon = true }.start()
    }

    private fun accept() {
        while (!closed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            client.tcpNoDelay = true
            XsbugConnection(client).also { it.start(this) }
        }
    }

    internal fun loggedIn(connection: XsbugConnection, login: XsbugLogin) {
        val previous = this.connection
        this.connection = connection
        if (previous != null && previous !== connection) previous.close()
        listener.connected(connection, login)
    }

    internal fun received(connection: XsbugConnection, message: XsbugMessage) = listener.message(connection, message)

    internal fun ended(connection: XsbugConnection) {
        if (this.connection === connection) {
            this.connection = null
            listener.disconnected(connection)
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        connection?.close()
    }

    /** Something already listens where the server would: xsbug, or another debug session. */
    class PortInUse(port: Int) : IOException("Port $port is in use")

    companion object {
        const val PORT = 5002

        /**
         * A server on the loopback address. pebble-tool connects to `localhost`, so anything
         * already listening there — on IPv4 or IPv6 — would get the connection instead: that is
         * checked first, since some systems let a second listener share the port.
         */
        fun bind(port: Int = PORT, listener: Listener): XsbugServer {
            if (port != 0 && listening(port)) throw PortInUse(port)
            val socket = ServerSocket()
            try {
                socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            } catch (e: IOException) {
                socket.close()
                throw if (e is java.net.BindException) PortInUse(port) else e
            }
            return XsbugServer(socket, listener).also { it.start() }
        }

        private fun listening(port: Int): Boolean = listOf("127.0.0.1", "::1").any { host ->
            runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 300) } }.isSuccess
        }
    }
}

/**
 * One run of the app, connected through pebble-tool.
 *
 * Commands are written whole, under a lock. A [request] — `select` or `toggle` — is answered by
 * the next view the engine sends: it answers in order, and nothing else it sends unasked is a
 * view, so a queue of pending replies is all the bookkeeping there is.
 */
class XsbugConnection internal constructor(private val socket: Socket) {

    private val output = socket.getOutputStream()
    private val writeLock = Any()
    private val pending = ConcurrentLinkedQueue<CompletableFuture<XsbugMessage>>()
    private val ended = CountDownLatch(1)

    @Volatile
    var login: XsbugLogin? = null
        private set

    val isOpen: Boolean get() = ended.count > 0

    internal fun start(server: XsbugServer) {
        Thread({ read(server) }, "Alloy debugger connection").apply { isDaemon = true }.start()
    }

    private fun read(server: XsbugServer) {
        val parser = XsbugParser()
        val buffer = ByteArray(16 * 1024)
        try {
            val input = socket.getInputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                for (message in parser.feed(buffer, count)) dispatch(server, message)
            }
        } catch (_: IOException) {
        } finally {
            ended.countDown()
            failPending(SocketException("The app disconnected from the debugger"))
            runCatching { socket.close() }
            if (login != null) server.ended(this)
        }
    }

    private fun dispatch(server: XsbugServer, message: XsbugMessage) {
        val login = message.login
        when {
            login != null -> {
                this.login = login
                server.loggedIn(this, login)
            }
            message.isView -> pending.poll()?.complete(message) ?: server.received(this, message)
            else -> {
                if (message.breakAt != null) failPending(IllegalStateException("The app stopped again"))
                server.received(this, message)
            }
        }
    }

    /** Writes a command; false if the connection has gone. */
    fun send(command: ByteArray): Boolean = synchronized(writeLock) {
        try {
            output.write(command)
            output.flush()
            true
        } catch (_: IOException) {
            false
        }
    }

    /** Writes a command the engine answers with a view, and gives that view when it comes. */
    fun request(command: ByteArray): CompletableFuture<XsbugMessage> {
        val reply = CompletableFuture<XsbugMessage>()
        synchronized(writeLock) {
            pending += reply
            if (!send(command)) {
                pending.remove(reply)
                reply.completeExceptionally(SocketException("The app is no longer connected"))
            }
        }
        return reply
    }

    /**
     * Logs out and waits for pebble-tool to hang up — which it does once the engine has let go —
     * so the app is running on its own before anything stops pebble-tool.
     */
    fun logoutAndAwaitClose(timeoutMillis: Long): Boolean {
        if (!send(XsbugCommands.logout())) return !isOpen
        return ended.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    fun close() {
        runCatching { socket.close() }
    }

    private fun failPending(cause: Throwable) {
        while (true) pending.poll()?.completeExceptionally(cause) ?: break
    }
}
