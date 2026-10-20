package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.debug.gdb.GdbDebugProcess
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** When the agent's debug start may answer: after the app's second start, when there is one. */
class PebbleDebuggerTest {

    @Test
    fun `an attach without breakpoints is settled at once`() {
        assertTrue(PebbleDebugger.relaunchSettled("App install succeeded.\n${GdbDebugProcess.ATTACHED}\n"))
    }

    @Test
    fun `an attach that starts the app again is settled once it has, or could not`() {
        val attached = "${GdbDebugProcess.ATTACHED} ${GdbDebugProcess.RELAUNCHING}\n"
        assertFalse(PebbleDebugger.relaunchSettled(attached))
        assertTrue(PebbleDebugger.relaunchSettled(attached + GdbDebugProcess.RELAUNCHED + "\n"))
        assertTrue(PebbleDebugger.relaunchSettled(attached + GdbDebugProcess.NOT_RELAUNCHED + " pebble install timed out.\n"))
    }
}
