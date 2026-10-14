package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.debug.XsbugCommands.Breakpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class XsbugCommandsTest {

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun `every command is a line of its own`() {
        assertEquals("\r\n<go/>\r\n", text(XsbugCommands.go()))
        assertEquals("\r\n<step/>\r\n", text(XsbugCommands.step()))
        assertEquals("\r\n<step-inside/>\r\n", text(XsbugCommands.stepInside()))
        assertEquals("\r\n<step-outside/>\r\n", text(XsbugCommands.stepOutside()))
        assertEquals("\r\n<logout/>\r\n", text(XsbugCommands.logout()))
        assertEquals(text(XsbugCommands.stepInside()), text(XsbugCommands.pause()))
    }

    @Test
    fun `breakpoints by path and line`() {
        val main = Breakpoint("/work/w/src/embeddedjs/main.js", 16)
        assertEquals("\r\n<set-breakpoint path=\"/work/w/src/embeddedjs/main.js\" line=\"16\" id=\"@0\"/>\r\n", text(XsbugCommands.setBreakpoint(main)))
        assertEquals("\r\n<clear-breakpoint path=\"/work/w/src/embeddedjs/main.js\" line=\"16\" id=\"@0\"/>\r\n", text(XsbugCommands.clearBreakpoint(main)))
        assertEquals(
            "\r\n<set-all-breakpoints><breakpoint path=\"/work/w/src/embeddedjs/main.js\" line=\"16\"/><breakpoint path=\"exceptions\" line=\"0\"/></set-all-breakpoints>\r\n",
            text(XsbugCommands.setAllBreakpoints(listOf(main, XsbugCommands.EXCEPTIONS))),
        )
        assertEquals("\r\n<set-all-breakpoints></set-all-breakpoints>\r\n", text(XsbugCommands.setAllBreakpoints(emptyList())))
    }

    @Test
    fun `a path is sent as it is, and refused when the engine could not read it`() {
        assertEquals(
            "\r\n<set-breakpoint path=\"/work/café & <x>/main.js\" line=\"1\" id=\"@0\"/>\r\n",
            text(XsbugCommands.setBreakpoint(Breakpoint("/work/café & <x>/main.js", 1))),
        )
        assertFalse(XsbugCommands.canSend("/work/\"quoted\"/main.js"))
        assertThrows<IllegalArgumentException> { XsbugCommands.setBreakpoint(Breakpoint("/a\nb.js", 1)) }
    }

    @Test
    fun `ids are the engine's addresses`() {
        assertEquals("\r\n<select id=\"@2002AC34\"/>\r\n", text(XsbugCommands.select("@2002ac34")))
        assertEquals("\r\n<toggle id=\"@20028684\"/>\r\n", text(XsbugCommands.toggle("20028684")))
        assertThrows<IllegalArgumentException> { XsbugCommands.toggle("main.render") }
    }
}
