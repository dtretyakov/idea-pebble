package com.github.dtretyakov.pebble.debug

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import java.util.Collections
import java.util.WeakHashMap

/**
 * What the Pebble debuggers last said of each breakpoint: set where asked, or not, and why. The
 * session shows it as the breakpoint's icon, which nothing outside the UI can read; agents ask here.
 */
@Service(Service.Level.PROJECT)
class BreakpointStates {

    /** Null message: verified. */
    data class State(val verified: Boolean, val message: String?)

    private val states = Collections.synchronizedMap(WeakHashMap<XBreakpoint<*>, State>())

    fun of(breakpoint: XBreakpoint<*>): State? = states[breakpoint]

    internal fun record(breakpoint: XBreakpoint<*>, state: State) {
        states[breakpoint] = state
    }

    companion object {
        fun getInstance(project: Project): BreakpointStates = project.service()
    }
}

/** [XDebugSession.setBreakpointVerified], remembered for [BreakpointStates]. */
internal fun XDebugSession.verify(breakpoint: XLineBreakpoint<*>) {
    BreakpointStates.getInstance(project).record(breakpoint, BreakpointStates.State(true, null))
    setBreakpointVerified(breakpoint)
}

/** [XDebugSession.setBreakpointInvalid], remembered for [BreakpointStates]. */
internal fun XDebugSession.invalidate(breakpoint: XLineBreakpoint<*>, message: String?) {
    BreakpointStates.getInstance(project).record(breakpoint, BreakpointStates.State(false, message))
    setBreakpointInvalid(breakpoint, message)
}
