package com.github.dtretyakov.pebble.debug

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Against a real session: the bytes an Alloy watchface on the emery emulator sent its debugger
 * while it logged in, hit a breakpoint in `draw()`, was stepped and expanded, and logged out.
 */
class XsbugParserTest {

    private val session = javaClass.getResource("/xsbug/session.bin")!!.readBytes()
    private val main = "/work/watchface/src/embeddedjs/main.js"

    private fun messages(bytes: ByteArray = session) = XsbugParser().feed(bytes)

    @Test
    fun `the session opens with a login`() {
        val login = messages().first().login!!
        assertEquals("main", login.name)
        assertTrue(login.version.startsWith("XS 17."), login.version)
    }

    @Test
    fun `a break carries the stack, the top frame's variables and where it stopped`() {
        val stop = messages().first { it.breakAt != null }
        assertEquals(XsbugBreak(main, 16, "# Break: breakpoint!\n"), stop.breakAt)
        assertEquals("breakpoint", stop.breakAt!!.reason)
        assertFalse(stop.breakAt!!.isException)

        val frames = stop.frames!!
        assertEquals(listOf("draw", "(anonymous-0)", "(host)", "(host)"), frames.map { it.name })
        assertEquals(main, frames[0].path)
        assertEquals(16, frames[0].line)
        assertNull(frames[2].path)
        assertTrue(frames.all { it.id.matches(Regex("@[0-9A-F]+")) })

        val locals = stop.local!!.properties.associateBy { it.name }
        assertTrue(locals.getValue("msg").value!!.matches(Regex("'\\d\\d:\\d\\d:\\d\\d'")), locals.getValue("msg").toString())
        assertNull(locals.getValue("width").value, "not yet assigned")
        assertTrue(locals.getValue("render").expandable)
        assertFalse(locals.getValue("render").expanded)
        assertTrue(locals.getValue("render").objectId!!.startsWith("@"))
        assertFalse(locals.getValue("white").expandable)
        assertTrue(stop.global!!.any { it.name == "console" })
        assertFalse(stop.isView)
    }

    @Test
    fun `steps say so, and land on the lines stepped to`() {
        val breaks = messages().mapNotNull { it.breakAt }
        assertEquals(listOf(16, 18, 19, 16, 16, 16), breaks.map { it.line })
        assertEquals("step", breaks[1].reason)
    }

    @Test
    fun `select and toggle are answered with views, and an expanded object holds its properties`() {
        val views = messages().filter { it.isView }
        assertEquals(3, views.size)
        assertEquals("(anonymous-0)", views[0].local!!.frame.name)
        assertEquals("draw", views[1].local!!.frame.name)

        val function = views[2].local!!.properties.first { it.name == "(function)" }
        assertTrue(function.expanded)
        assertTrue(function.children.isNotEmpty())
    }

    @Test
    fun `console output arrives as logs, with references read`() {
        val logs = messages().flatMap { it.logs }.map { it.text }
        assertTrue("Hello, Watchface." in logs, logs.toString())
        assertTrue("\n" in logs)
        assertTrue(logs.any { it.contains("\"pebble.moddable.tech\"") }, logs.toString())
    }

    @Test
    fun `fed a byte at a time, the same messages come out`() {
        val parser = XsbugParser()
        val oneByOne = session.indices.flatMap { parser.feed(byteArrayOf(session[it])) }
        val whole = messages()
        assertEquals(whole.size, oneByOne.size)
        assertEquals(whole.map { it.toString() }, oneByOne.map { it.toString() })
    }

    @Test
    fun `characters split between reads, control characters, and two messages in one read`() {
        val text = "\r\n<xsbug><log>caf\u00e9 &#1;&#60;&amp;</log></xsbug>\r\n\r\n<xsbug><log path=\"/a &#34;b&#34;.js\" line=\"3\"># Exception: boom!&#10;</log></xsbug>\r\n"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val split = bytes.indexOfFirst { it == 0xC3.toByte() } + 1
        val parser = XsbugParser()
        val messages = parser.feed(bytes.copyOfRange(0, split)) + parser.feed(bytes.copyOfRange(split, bytes.size))

        assertEquals(2, messages.size)
        assertEquals("caf\u00e9 \u0001<&", messages[0].logs.single().text)
        val problem = messages[1].logs.single()
        assertEquals("/a \"b\".js", problem.path)
        assertEquals(3, problem.line)
        assertTrue(problem.isProblem)
    }

    @Test
    fun `nothing is given out until a message ends`() {
        val parser = XsbugParser()
        assertTrue(parser.feed("\r\n<xsbug><log>half".toByteArray()).isEmpty())
        assertEquals("half done", parser.feed(" done</log></xsbug>".toByteArray()).single().logs.single().text)
    }
}
