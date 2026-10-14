package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.debug.AlloyBreakpointTargets.Made
import com.github.dtretyakov.pebble.debug.XsbugCommands.Breakpoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** What the engine is told for a breakpoint, by what the IDE's breakpoint says. */
class AlloyBreakpointTargetsTest {

    private val compiled = mutableListOf<String>()
    private val compiler: (String) -> XscCompiler.Result? = { expression ->
        compiled += expression
        if ("(" in expression && !expression.contains(")")) XscCompiler.Result.Failed("missing )") else XscCompiler.Result.Compiled("AB")
    }

    private fun made(condition: String? = null, log: String? = null, logMessage: Boolean = false, suspends: Boolean = true, compile: (String) -> XscCompiler.Result? = compiler) =
        AlloyBreakpointTargets.of("/w/main.js", 16, "main.js", condition, log, logMessage, suspends, compile)

    @Test
    fun `a plain breakpoint is just where it is`() {
        assertEquals(Made.Target(Breakpoint("/w/main.js", 16)), made())
        assertEquals(emptyList<String>(), compiled)
    }

    @Test
    fun `a condition is compiled for the engine to decide`() {
        assertEquals(Made.Target(Breakpoint("/w/main.js", 16, condition = "AB")), made(condition = " msg.length > 3 "))
        assertEquals(listOf("msg.length > 3"), compiled)
    }

    @Test
    fun `one that only logs becomes a trace, which does not stop`() {
        assertEquals(Made.Target(Breakpoint("/w/main.js", 16, trace = "AB")), made(log = "msg", logMessage = true, suspends = false))
        assertEquals(listOf("\"Breakpoint reached at main.js:16\" + \" \" + (msg)"), compiled)
    }

    @Test
    fun `one that neither stops nor logs is not sent, and one that stops logs on its own`() {
        assertEquals(Made.Nothing, made(suspends = false))
        assertEquals(Made.Target(Breakpoint("/w/main.js", 16)), made(log = "msg", suspends = true))
    }

    @Test
    fun `what cannot be compiled makes the breakpoint invalid`() {
        assertEquals(Made.Invalid("Cannot compile msg.(: missing )"), made(condition = "msg.("))
        assertEquals(Made.Invalid("This SDK has no xsc to compile msg with"), made(condition = "msg", compile = { null }))
    }
}
