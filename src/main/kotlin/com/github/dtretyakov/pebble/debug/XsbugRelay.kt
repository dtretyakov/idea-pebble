package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.sdk.HostCommand
import com.github.dtretyakov.pebble.sdk.ToolHost
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * The Alloy debugger's connections, when pebble-tool runs in WSL.
 *
 * pebble-tool looks for xsbug on `localhost:5002`, with no option for another host, and WSL's
 * localhost is not Windows' under WSL's default NAT networking: the IDE's server, listening on
 * Windows' loopback, is out of its reach. The other way round does work — Windows reaches a port
 * a program listens on inside WSL through localhost — so the connection is turned around: a small
 * relay inside the distribution listens where pebble-tool looks, and on a second port the IDE
 * connects to. Each connection the IDE makes is held until pebble-tool makes one, then the two are
 * joined, byte for byte; the relay says `PAIRED` first, so the IDE has one connection waiting at a
 * time and not a queue of them. Nothing listens on Windows, and no firewall rule is involved.
 *
 * Python, because the distribution has it — Ubuntu's own tools are written in it — and pebble-tool
 * needs nothing else to be there. Ends with the IDE's end of it: stopping `wsl.exe` stops it.
 */
class XsbugRelay private constructor(private val process: Process, override val port: Int) : XsbugServer.Source {

    @Volatile
    private var closed = false

    override fun next(): Socket? {
        while (!closed && process.isAlive) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress("127.0.0.1", port), CONNECT_MILLIS)
                // Held here until pebble-tool connects on the other side.
                val paired = readLine(socket)
                if (paired == PAIRED) return socket
                socket.close()
            } catch (_: IOException) {
                runCatching { socket.close() }
                if (closed) return null
                Thread.sleep(RETRY_MILLIS)
            }
        }
        return null
    }

    override fun close() {
        closed = true
        process.destroy()
        if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    /** One line, byte by byte: what follows it is the debugger's and must not be read here. */
    private fun readLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == '\n'.code) return line.toString()
            line.append(byte.toChar())
        }
    }

    class PortInUse : IOException("Port ${XsbugServer.PORT} is in use inside WSL")

    companion object {
        private const val PAIRED = "PAIRED"
        private const val CONNECT_MILLIS = 2_000
        private const val RETRY_MILLIS = 200L
        private const val START_SECONDS = 30L

        /**
         * Starts the relay with [launch] — the IDE's way of running [HostCommand]s — and waits
         * until it listens. Fails with [PortInUse] when something in the distribution already
         * listens on 5002: xsbug, or another session's relay.
         */
        fun start(host: ToolHost, launch: (HostCommand) -> Process, port: Int = XsbugServer.PORT): XsbugRelay {
            val python = host.idePath("/usr/bin/python3") ?: Path.of("/usr/bin/python3")
            val process = launch(host.command(python, listOf("-u", "-c", bootstrap(), port.toString()), null))
            val reader = process.inputStream.bufferedReader()
            // The relay's own line, past whatever the login shell's profile prints before it.
            val first = runCatching {
                java.util.concurrent.CompletableFuture.supplyAsync {
                    generateSequence { reader.readLine() }.firstOrNull { it == "IN-USE" || it.startsWith("LISTENING ") }
                }.get(START_SECONDS, TimeUnit.SECONDS)
            }.getOrNull()
            when {
                first == "IN-USE" -> {
                    process.destroy()
                    throw PortInUse()
                }
                first?.startsWith("LISTENING ") == true -> {
                    val listening = first.removePrefix("LISTENING ").trim().toInt()
                    return XsbugRelay(process, listening)
                }
                else -> {
                    val error = runCatching { process.errorStream.bufferedReader().readText() }.getOrDefault("")
                    process.destroyForcibly()
                    throw IOException("The Alloy debugger's relay did not start in ${host.presentableName}: " + (first ?: error.lines().lastOrNull { it.isNotBlank() } ?: "no answer"))
                }
            }
        }

        /** The script, as one word with nothing in it a shell or Windows' quoting could take apart. */
        internal fun bootstrap(): String =
            "import base64;exec(base64.b64decode('" + Base64.getEncoder().encodeToString(SCRIPT.toByteArray()) + "'))"

        internal val SCRIPT = """
            import os, socket, sys, threading

            def listen(port):
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(('127.0.0.1', port))
                s.listen(8)
                return s

            try:
                engine = listen(int(sys.argv[1]))
            except OSError:
                print('IN-USE', flush=True)
                sys.exit(2)
            ide = listen(0)
            print('LISTENING %d' % ide.getsockname()[1], flush=True)

            def pipe(a, b):
                try:
                    while True:
                        data = a.recv(65536)
                        if not data:
                            break
                        b.sendall(data)
                except OSError:
                    pass
                for s in (a, b):
                    try:
                        s.shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass

            def watch():
                sys.stdin.read()
                os._exit(0)

            threading.Thread(target=watch, daemon=True).start()
            while True:
                i, _ = ide.accept()
                e, _ = engine.accept()
                i.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                e.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                i.sendall(b'PAIRED\n')
                threading.Thread(target=pipe, args=(e, i), daemon=True).start()
                threading.Thread(target=pipe, args=(i, e), daemon=True).start()
        """.trimIndent()
    }
}
