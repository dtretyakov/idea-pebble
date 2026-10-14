package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.run.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LogBufferTest {

    @Test
    fun `lines are numbered, coloured ones lose their colours but keep their level`() {
        val buffer = LogBuffer()
        buffer.append("\u001b[41m\u001b[1m\u001b[37m[06:03:16] main.c:3> an error\u001b[0m\n[06:03:16] main.c:5> an ", LogBuffer.Source.APP)
        buffer.append("info\nInstalling app...\n", LogBuffer.Source.APP)
        val page = buffer.read()
        assertEquals(listOf(0L, 1L, 2L), page.lines.map { it.index })
        assertEquals("[06:03:16] main.c:3> an error", page.lines[0].text)
        assertEquals(listOf("ERROR", "INFO", null), page.lines.map { it.level })
        assertEquals(2, page.next)
    }

    @Test
    fun `reading on from a cursor gives only what is new`() {
        val buffer = LogBuffer()
        buffer.append("one\ntwo\n", LogBuffer.Source.APP)
        val first = buffer.read()
        buffer.append("three\n", LogBuffer.Source.IDE)
        val second = buffer.read(first.next)
        assertEquals(listOf("three"), second.lines.map { it.text })
        assertEquals("ide", second.lines.single().source)
        assertEquals(0, buffer.read(second.next).lines.size)
        assertEquals(second.next, buffer.read(second.next).next)
    }

    @Test
    fun `a limit leaves the cursor at the last line returned, and filters apply`() {
        val buffer = LogBuffer()
        buffer.append("\u001b[36m[06:03:16] a.c:1> verbose\u001b[0m\n\u001b[1m\u001b[31m[06:03:16] a.c:2> warning\u001b[0m\nplain tool line\n", LogBuffer.Source.APP)
        val one = buffer.read(limit = 1)
        assertEquals(0, one.next)
        assertEquals(listOf("[06:03:16] a.c:2> warning", "plain tool line"), buffer.read(minimum = LogLevel.WARNING).lines.map { it.text })
        assertEquals(listOf("plain tool line"), buffer.read(contains = "TOOL").lines.map { it.text })
    }

    @Test
    fun `old lines go, and a reader behind them is told how many it missed`() {
        val buffer = LogBuffer(capacity = 3)
        buffer.append((0 until 5).joinToString("") { "line $it\n" }, LogBuffer.Source.APP)
        val page = buffer.read()
        assertEquals(listOf("line 2", "line 3", "line 4"), page.lines.map { it.text })
        assertEquals(2, page.dropped)
    }

    @Test
    fun `an unfinished line is kept for when it ends, or for the process's end`() {
        val buffer = LogBuffer()
        buffer.append("half", LogBuffer.Source.APP)
        assertEquals(0, buffer.read().lines.size)
        buffer.flush()
        assertEquals("half", buffer.read().lines.single().text)
        assertNull(buffer.read().lines.single().level)
    }
}
