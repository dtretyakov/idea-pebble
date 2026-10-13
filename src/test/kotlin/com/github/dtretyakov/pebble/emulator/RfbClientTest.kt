package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Against a server that answers the way QEMU's does, byte for byte. */
class RfbClientTest {

    @Test
    fun `a frame is read in the pixel format asked for, and keys reach the server`() {
        ServerSocket(0).use { server ->
            val received = ArrayBlockingQueue<IntArray>(4)
            thread(isDaemon = true) {
                server.accept().use { socket ->
                    val input = DataInputStream(socket.getInputStream())
                    val out = DataOutputStream(socket.getOutputStream())
                    out.write("RFB 003.008\n".toByteArray())
                    input.readFully(ByteArray(12))
                    out.write(byteArrayOf(1, 1)) // one security type: None
                    check(input.readUnsignedByte() == 1)
                    out.writeInt(0)
                    input.readUnsignedByte() // shared
                    out.writeShort(2)
                    out.writeShort(1)
                    out.write(ByteArray(16))
                    out.writeInt(4)
                    out.write("QEMU".toByteArray())
                    out.flush()

                    input.readFully(ByteArray(20)) // SetPixelFormat
                    input.readFully(ByteArray(4))
                    input.readFully(ByteArray(8)) // SetEncodings with two
                    input.readFully(ByteArray(10)) // FramebufferUpdateRequest

                    out.writeByte(0)
                    out.writeByte(0)
                    out.writeShort(1)
                    out.writeShort(0); out.writeShort(0); out.writeShort(2); out.writeShort(1); out.writeInt(0)
                    out.write(byteArrayOf(0x30, 0x20, 0x10, 0, 0x03, 0x02, 0x01, 0)) // two pixels, BGRX
                    out.flush()

                    // Then whatever the client sends next: an incremental request and a key event.
                    while (true) {
                        when (val type = input.readUnsignedByte()) {
                            3 -> input.readFully(ByteArray(9))
                            4 -> received.put(intArrayOf(type, input.readUnsignedByte(), input.readUnsignedShort(), input.readInt()))
                            else -> error("unexpected $type")
                        }
                    }
                }
            }

            val frames = ArrayBlockingQueue<IntArray>(4)
            val done = CountDownLatch(1)
            val client = RfbClient("127.0.0.1", server.localPort, object : RfbClient.Listener {
                override fun frame(image: BufferedImage) {
                    frames.put(intArrayOf(image.width, image.height, image.getRGB(0, 0) and 0xFFFFFF, image.getRGB(1, 0) and 0xFFFFFF))
                }

                override fun disconnected(cause: Throwable?) = done.countDown()
            })
            client.start()

            frames.poll(5, TimeUnit.SECONDS) // the empty frame on connect
            assertArrayEquals(intArrayOf(2, 1, 0x102030, 0x010203), frames.poll(5, TimeUnit.SECONDS))

            client.key(RfbClient.KEY_DOWN, true)
            assertArrayEquals(intArrayOf(4, 1, 0, RfbClient.KEY_DOWN), received.poll(5, TimeUnit.SECONDS))

            client.close()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(0, received.size)
        }
    }
}
