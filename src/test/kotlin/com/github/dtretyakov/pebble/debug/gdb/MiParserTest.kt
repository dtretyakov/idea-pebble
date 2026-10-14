package com.github.dtretyakov.pebble.debug.gdb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Against a real session: what the SDK's gdb printed, attached to the emery emulator, while it
 * set breakpoints, stopped in a click handler, listed the stack and variables, stepped, was
 * interrupted and detached.
 */
class MiParserTest {

    private val lines = javaClass.getResource("/gdb/session.mi")!!.readText().lines()
    private val records = lines.mapNotNull(MiParser::parse)

    @Test
    fun `every line of the session is a record`() {
        assertEquals(lines.count { it.isNotBlank() }, records.size)
        assertEquals(MiRecord.Kind.PROMPT, records.first { it.kind == MiRecord.Kind.PROMPT }.kind)
    }

    @Test
    fun `a result answers its command by number`() {
        val connected = records.first { it.token == 2 }
        assertEquals(MiRecord.Kind.RESULT, connected.kind)
        assertEquals("connected", connected.type)

        val breakpoint = records.first { it.token == 5 }.tuple("bkpt")!!
        assertEquals("2", breakpoint.text("number"))
        assertEquals("select_clicked", breakpoint.text("func"))
        assertEquals("/work/app/src/c/fixture-c.c", breakpoint.text("fullname"))
        assertEquals(listOf(MiValue.Text("i1")), breakpoint.list("thread-groups"))
    }

    @Test
    fun `a stop says why, and where`() {
        val hit = records.first { it.kind == MiRecord.Kind.EXEC && it.text("reason") == "breakpoint-hit" }
        assertEquals("stopped", hit.type)
        assertEquals("2", hit.text("bkptno"))
        val frame = GdbFrame.of(hit.tuple("frame")!!)
        assertEquals(GdbFrame(0, "select_clicked", "../src/c/fixture-c.c", "/work/app/src/c/fixture-c.c", 4, "0x200504c6"), frame)
        val args = hit.tuple("frame")!!.list("args").map { (it as MiValue.Tuple).text("name") }
        assertEquals(listOf("recognizer", "context"), args)
    }

    @Test
    fun `the stack lists the app's frames over the firmware's`() {
        val frames = GdbFrame.list(records.first { it.token == 9 })
        assertEquals(8, frames.size)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), frames.map { it.level })
        assertEquals("prv_dispatch_event", frames[1].function)
        assertEquals("./build/../src/fw/applib/ui/click.c", frames[1].fullname)
        assertEquals("main", frames[5].function)
        assertEquals("??", frames[7].function)
        assertNull(frames[7].file)
    }

    @Test
    fun `variables and variable objects`() {
        val variables = records.first { it.token == 10 }.list("variables").map { it as MiValue.Tuple }
        assertEquals(listOf("recognizer", "context"), variables.map { it.text("name") })
        assertEquals("void *", variables[1].text("type"))

        val created = GdbVariable.of(records.first { it.token == 12 }.results)
        assertEquals(GdbVariable("var1", "var1", 0, "0x200505dc", "void *"), created)
    }

    @Test
    fun `console text is unescaped, the target's own interrupt says so`() {
        val console = records.filter { it.kind == MiRecord.Kind.CONSOLE }.joinToString("") { it.text!! }
        assertTrue("4\t  APP_LOG(APP_LOG_LEVEL_INFO, \"select\");\n" in console, console)
        assertTrue(console.contains("Program received signal SIGINT, Interrupt.\n"))
        val warning = records.first { it.kind == MiRecord.Kind.LOG }
        assertTrue(warning.text!!.startsWith("warning: 266\t"))
        val interrupted = records.last { it.kind == MiRecord.Kind.EXEC && it.type == "stopped" }
        assertEquals("signal-received", interrupted.text("reason"))
        assertEquals("SIGINT", interrupted.text("signal-name"))
    }

    @Test
    fun `errors, octal bytes as UTF-8, and lines that are not MI`() {
        val error = MiParser.parse("27^error,msg=\"Cannot execute this command while the target is running.\\nUse the \\\"interrupt\\\" command\"")!!
        assertTrue(error.isError)
        assertEquals(27, error.token)
        assertEquals("Cannot execute this command while the target is running.\nUse the \"interrupt\" command", error.text("msg"))
        assertFalse(MiParser.parse("1^done")!!.isError)

        assertEquals("café \"x\"", MiParser.parse("~\"caf\\303\\251 \\\"x\\\"\"")!!.text)
        assertEquals("0x2005 \"none\"", MiParser.parse("5^done,value=\"0x2005 \\\"none\\\"\"")!!.text("value"))
        assertNull(MiParser.parse(""))
        assertNull(MiParser.parse("Reading symbols from x..."))
    }

    @Test
    fun `quoting for a command's parameter is undone by reading it back`() {
        val text = "/work/my app/a \"b\"\\c.c"
        assertEquals("\"/work/my app/a \\\"b\\\"\\\\c.c\"", MiParser.quote(text))
        assertEquals(text, MiParser.parse("~${MiParser.quote(text)}")!!.text)
    }
}
