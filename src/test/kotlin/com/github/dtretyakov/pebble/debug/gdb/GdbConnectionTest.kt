package com.github.dtretyakov.pebble.debug.gdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class GdbConnectionTest {

    private val gdb = FakeGdb()
    private val records = LinkedBlockingQueue<MiRecord>()
    private val closed = CountDownLatch(1)
    private val connection = GdbConnection(
        gdb.input,
        gdb.output,
        object : GdbConnection.Listener {
            override fun record(record: MiRecord) {
                records += record
            }

            override fun closed() = closed.countDown()
        },
    ).start()

    @Test
    fun `each command gets its own answer, and an error answer is thrown with gdb's words`() {
        gdb.answer("-break-insert app_crashed", "{t}^done,bkpt={number=\"1\"}")
        gdb.answer("-break-insert nope", "{t}^error,msg=\"Function \\\"nope\\\" not defined.\"")
        assertEquals("1", connection.call("-break-insert app_crashed").tuple("bkpt")!!.text("number"))
        val error = assertThrows<GdbConnection.GdbError> { connection.call("-break-insert nope") }
        assertEquals("Function \"nope\" not defined.", error.message)
        assertTrue(connection.request("-break-insert nope").get(5, TimeUnit.SECONDS).isError)
    }

    @Test
    fun `the target running and stopping is followed, and told, in order`() {
        gdb.answer("-exec-continue", "{t}^running", "*running,thread-id=\"all\"", "(gdb) ")
        assertFalse(connection.running)
        connection.call("-exec-continue")
        assertTrue(connection.running, "set by ^running, before the answer")
        assertEquals("running", records.poll(5, TimeUnit.SECONDS)!!.type)

        gdb.emit("~\"Breakpoint 2, select_clicked\\n\"")
        gdb.emit("*stopped,reason=\"breakpoint-hit\",bkptno=\"2\",thread-id=\"1\"")
        assertEquals(MiRecord.Kind.CONSOLE, records.poll(5, TimeUnit.SECONDS)!!.kind)
        val stopped = records.poll(5, TimeUnit.SECONDS)!!
        assertEquals("breakpoint-hit", stopped.text("reason"))
        assertFalse(connection.running)
    }

    @Test
    fun `when gdb goes, what waits for it is told so`() {
        gdb.answer("-gdb-exit")
        val pending = connection.request("-gdb-exit")
        gdb.exit()
        assertTrue(closed.await(5, TimeUnit.SECONDS))
        assertTrue(connection.isClosed)
        assertThrows<GdbConnection.GdbError> { connection.call("-exec-continue", 2) }
        assertTrue(pending.isDone)
    }
}
