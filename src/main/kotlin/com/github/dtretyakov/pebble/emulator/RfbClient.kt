package com.github.dtretyakov.pebble.emulator

import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Just enough of the RFB protocol — VNC, RFC 6143 — to watch QEMU's screen and press its keys.
 *
 * QEMU's VNC server needs no authentication on localhost and speaks every encoding a client might
 * ask for, so this asks for the simplest: Raw, 32-bit true colour. A watch screen is at most
 * 260×260, a full frame about 270 KB, and on localhost Raw costs nothing that compression would
 * win back. Incremental updates make it a push: QEMU answers a request only when something on the
 * screen changed.
 *
 * Owns one thread, which reads; key and pointer events are written from the caller's.
 */
class RfbClient(private val host: String, private val port: Int, private val listener: Listener) : Closeable {

    interface Listener {
        /** A new frame: [image] is the whole framebuffer, the same instance each time until a resize. */
        fun frame(image: BufferedImage)

        /** The connection ended — closed by us, by QEMU, or by an error, which is then [cause]. */
        fun disconnected(cause: Throwable?)
    }

    @Volatile
    private var socket: Socket? = null
    private var output: DataOutputStream? = null

    @Volatile
    private var closed = false

    fun start() {
        Thread(::run, "Pebble emulator screen").apply { isDaemon = true }.start()
    }

    fun key(keysym: Int, down: Boolean) = send { out ->
        out.writeByte(KEY_EVENT)
        out.writeByte(if (down) 1 else 0)
        out.writeShort(0)
        out.writeInt(keysym)
    }

    /**
     * A press is button 1 held at [x], [y]; a release is the same position with no buttons.
     *
     * A press is preceded by a move there with no buttons, as a real mouse arrives before it
     * clicks: the first press after connecting, sent alone, reaches the watch without its
     * Touchdown.
     */
    fun pointer(x: Int, y: Int, pressed: Boolean) = send { out ->
        if (pressed) writePointer(out, x, y, 0)
        writePointer(out, x, y, if (pressed) 1 else 0)
    }

    private fun writePointer(out: DataOutputStream, x: Int, y: Int, buttons: Int) {
        out.writeByte(POINTER_EVENT)
        out.writeByte(buttons)
        out.writeShort(x)
        out.writeShort(y)
    }

    override fun close() {
        closed = true
        runCatching { socket?.close() }
    }

    private fun send(write: (DataOutputStream) -> Unit) {
        val out = output ?: return
        try {
            synchronized(out) {
                write(out)
                out.flush()
            }
        } catch (_: IOException) {
            // The reader sees the same failure and reports it; a lost key press has nothing to add.
        }
    }

    private fun run() {
        var cause: Throwable? = null
        try {
            val socket = Socket().also { this.socket = it }
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            val input = DataInputStream(socket.getInputStream().buffered())
            val out = DataOutputStream(socket.getOutputStream().buffered()).also { output = it }

            var image = handshake(input, out)
            listener.frame(image)
            request(out, image, incremental = false)

            while (!closed) {
                when (val type = input.readUnsignedByte()) {
                    FRAMEBUFFER_UPDATE -> {
                        image = readUpdate(input, image)
                        listener.frame(image)
                        Thread.sleep(FRAME_INTERVAL_MILLIS)
                        request(out, image, incremental = true)
                    }
                    SET_COLOUR_MAP -> {
                        input.skipNBytes(3)
                        input.skipNBytes(input.readUnsignedShort() * 6L)
                    }
                    BELL -> Unit
                    SERVER_CUT_TEXT -> {
                        input.skipNBytes(3)
                        input.skipNBytes(input.readInt().toLong())
                    }
                    else -> throw IOException("Unexpected RFB message $type")
                }
            }
        } catch (e: Throwable) {
            if (!closed) cause = e
        } finally {
            runCatching { socket?.close() }
            listener.disconnected(cause)
        }
    }

    /** Version, no security, shared session; then the pixel format and encodings this reads. */
    private fun handshake(input: DataInputStream, out: DataOutputStream): BufferedImage {
        val version = ByteArray(12).also(input::readFully).decodeToString()
        if (!version.startsWith("RFB 003.")) throw IOException("Not a VNC server: $version")
        out.write("RFB 003.008\n".toByteArray())
        out.flush()

        val types = ByteArray(input.readUnsignedByte()).also(input::readFully)
        if (types.isEmpty()) throw IOException("VNC refused: ${readString(input)}")
        if (SECURITY_NONE.toByte() !in types) throw IOException("VNC asks for authentication, which QEMU's emulator does not")
        out.writeByte(SECURITY_NONE)
        out.flush()
        if (input.readInt() != 0) throw IOException("VNC refused: ${readString(input)}")

        out.writeByte(1) // share the desktop: pebble-tool's websockify may be connected too
        out.flush()
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        input.skipNBytes(16) // the server's pixel format, replaced below
        readString(input) // the desktop name

        out.writeByte(SET_PIXEL_FORMAT)
        out.write(ByteArray(3))
        out.write(byteArrayOf(32, 24, 0, 1)) // 32 bits, depth 24, little-endian, true colour
        out.writeShort(255)
        out.writeShort(255)
        out.writeShort(255)
        out.write(byteArrayOf(16, 8, 0)) // red, green and blue shifts: the int is 0x00RRGGBB
        out.write(ByteArray(3))

        out.writeByte(SET_ENCODINGS)
        out.writeByte(0)
        out.writeShort(2)
        out.writeInt(ENCODING_RAW)
        out.writeInt(ENCODING_DESKTOP_SIZE)
        out.flush()

        return BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    }

    private fun request(out: DataOutputStream, image: BufferedImage, incremental: Boolean) {
        synchronized(out) {
            out.writeByte(FRAMEBUFFER_UPDATE_REQUEST)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0)
            out.writeShort(0)
            out.writeShort(image.width)
            out.writeShort(image.height)
            out.flush()
        }
    }

    private fun readUpdate(input: DataInputStream, current: BufferedImage): BufferedImage {
        var image = current
        input.skipNBytes(1)
        repeat(input.readUnsignedShort()) {
            val x = input.readUnsignedShort()
            val y = input.readUnsignedShort()
            val width = input.readUnsignedShort()
            val height = input.readUnsignedShort()
            when (val encoding = input.readInt()) {
                ENCODING_DESKTOP_SIZE -> image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                ENCODING_RAW -> {
                    val bytes = ByteArray(width * height * 4).also(input::readFully)
                    val pixels = (image.raster.dataBuffer as DataBufferInt).data
                    for (row in 0 until height) {
                        val target = (y + row) * image.width + x
                        for (column in 0 until width) {
                            val at = (row * width + column) * 4
                            // Little-endian 0x00RRGGBB: blue first.
                            pixels[target + column] = (bytes[at].toInt() and 0xFF) or
                                ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                                ((bytes[at + 2].toInt() and 0xFF) shl 16)
                        }
                    }
                }
                else -> throw IOException("Unexpected RFB encoding $encoding")
            }
        }
        return image
    }

    private fun readString(input: DataInputStream): String =
        ByteArray(input.readInt()).also(input::readFully).decodeToString()

    companion object {
        /** X keysyms for the four buttons, as the emulator's own window maps them. */
        const val KEY_LEFT = 0xFF51
        const val KEY_UP = 0xFF52
        const val KEY_RIGHT = 0xFF53
        const val KEY_DOWN = 0xFF54

        private const val SECURITY_NONE = 1
        private const val SET_PIXEL_FORMAT = 0
        private const val SET_ENCODINGS = 2
        private const val FRAMEBUFFER_UPDATE_REQUEST = 3
        private const val KEY_EVENT = 4
        private const val POINTER_EVENT = 5
        private const val FRAMEBUFFER_UPDATE = 0
        private const val SET_COLOUR_MAP = 1
        private const val BELL = 2
        private const val SERVER_CUT_TEXT = 3
        private const val ENCODING_RAW = 0
        private const val ENCODING_DESKTOP_SIZE = -223
        private const val CONNECT_TIMEOUT_MILLIS = 2000

        /** At most about 30 frames a second: more than a watch animates at, and no busier. */
        private const val FRAME_INTERVAL_MILLIS = 33L
    }
}
