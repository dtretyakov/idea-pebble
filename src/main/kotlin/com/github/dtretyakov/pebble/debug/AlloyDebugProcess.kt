package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.debug.XsbugCommands.Breakpoint
import com.intellij.execution.ExecutionResult
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * An Alloy app in the IDE's debugger: the IDE side of xsbug's protocol, with the plugin in
 * xsbug's place on port 5002 ([XsbugServer]) and pebble-tool bridging the watch to it.
 *
 * The run's process is `pebble install … --logs`, and its console is the run's own. Every time the
 * app starts it logs in, and is answered with the breakpoints at once. A break shows the engine's
 * stack and the top frame's variables; other frames and objects are asked for as they are opened,
 * one request at a time, since the engine answers in order and keeps what is expanded itself.
 *
 * While a debugger is connected, the engine sends `console.log` to it rather than to the watch's
 * log, so the debugger writes it to the console.
 */
class AlloyDebugProcess(
    session: XDebugSession,
    private val server: XsbugServer,
    private val result: ExecutionResult,
) : XDebugProcess(session), XsbugServer.Listener {

    private val lineBreakpoints = ConcurrentHashMap<XLineBreakpoint<*>, Breakpoint>()

    @Volatile
    private var exceptionBreakpoint: XBreakpoint<*>? = null

    @Volatile
    internal var suspension: Suspension? = null
        private set

    /** Where frames and objects are asked for: one at a time, off the UI thread. */
    private val requests = AppExecutorUtil.createBoundedApplicationPoolExecutor("Alloy debugger requests", 1)

    init {
        session.setPauseActionSupported(true)
        server.listener = this
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = EditorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = listOfNotNull(
        AlloyLineBreakpointHandler(AlloyLineBreakpointType::class.java, this),
        AlloyBreakpoints.javascriptLineType()?.let { AlloyLineBreakpointHandler(AlloyLineBreakpointHandler.forType(it), this) },
        AlloyExceptionBreakpointHandler(this),
    ).toTypedArray()

    override fun doGetProcessHandler(): ProcessHandler = result.processHandler

    override fun createConsole(): ExecutionConsole = result.executionConsole

    // Breakpoints, kept for the next login and sent at once to a connected app.

    internal fun addLineBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        val file = breakpoint.sourcePosition?.file ?: return
        val path = file.toNioPathOrNull() ?: return
        if (!AlloyBreakpoints.isAlloySource(session.project, file)) return
        val enginePath = AlloySources.enginePath(path).toString()
        if (!XsbugCommands.canSend(enginePath)) {
            session.setBreakpointInvalid(breakpoint, "The Alloy debugger cannot be given a path with a double quote or a line break")
            return
        }
        val target = Breakpoint(enginePath, breakpoint.line + 1)
        lineBreakpoints[breakpoint] = target
        val connection = server.connection ?: return
        if (connection.send(XsbugCommands.setBreakpoint(target))) session.setBreakpointVerified(breakpoint)
    }

    internal fun removeLineBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        val target = lineBreakpoints.remove(breakpoint) ?: return
        if (lineBreakpoints.values.none { it == target }) server.connection?.send(XsbugCommands.clearBreakpoint(target))
    }

    internal fun setExceptionBreakpoint(breakpoint: XBreakpoint<*>?) {
        exceptionBreakpoint = breakpoint
        val connection = server.connection ?: return
        connection.send(if (breakpoint != null) XsbugCommands.setBreakpoint(XsbugCommands.EXCEPTIONS) else XsbugCommands.clearBreakpoint(XsbugCommands.EXCEPTIONS))
    }

    // The engine.

    override fun connected(connection: XsbugConnection, login: XsbugLogin) {
        val targets = lineBreakpoints.values.distinct() + listOfNotNull(XsbugCommands.EXCEPTIONS.takeIf { exceptionBreakpoint != null })
        connection.send(XsbugCommands.setAllBreakpoints(targets))
        lineBreakpoints.keys.forEach { session.setBreakpointVerified(it) }
        print("The app's JavaScript is connected to the debugger (${login.version}).\n", ProcessOutputTypes.SYSTEM)
    }

    override fun message(connection: XsbugConnection, message: XsbugMessage) {
        for (log in message.logs) {
            val located = if (log.path != null && log.line != null) "${log.path}:${log.line}: " else ""
            print(located + log.text, if (log.isProblem) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT)
        }
        val stop = message.breakAt ?: return
        val suspension = Suspension(connection, message.frames.orEmpty(), message)
        this.suspension = suspension
        val context = AlloySuspendContext(this, suspension)
        val line = stop.path?.let { path -> lineBreakpoints.entries.firstOrNull { it.value.path == path && it.value.line == stop.line }?.key }
        val exception = exceptionBreakpoint.takeIf { stop.isException }
        when {
            line != null -> if (!session.breakpointReached(line, null, context)) resume(context)
            exception != null -> if (!session.breakpointReached(exception, stop.reason, context)) resume(context)
            else -> session.positionReached(context)
        }
    }

    override fun disconnected(connection: XsbugConnection) {
        val wasSuspended = suspension != null
        suspension = null
        if (wasSuspended) session.sessionResumed()
        print("The app's JavaScript has disconnected from the debugger.\n", ProcessOutputTypes.SYSTEM)
    }

    // Running.

    override fun resume(context: XSuspendContext?) = proceed(XsbugCommands.go())

    override fun startStepOver(context: XSuspendContext?) = proceed(XsbugCommands.step())

    override fun startStepInto(context: XSuspendContext?) = proceed(XsbugCommands.stepInside())

    override fun startStepOut(context: XSuspendContext?) = proceed(XsbugCommands.stepOutside())

    override fun startPausing() {
        server.connection?.send(XsbugCommands.pause())
    }

    private fun proceed(command: ByteArray) {
        val current = suspension
        suspension = null
        (current?.connection ?: server.connection)?.send(command)
    }

    /**
     * Ends the session: logs out first, so the app runs on as if never debugged, then lets the
     * run's process go. Called when the process has ended or is being stopped.
     */
    override fun stop() {
        server.close()
        requests.shutdownNow()
    }

    /** Logs out and waits for the bridge to hang up; false if the app could not be told. */
    fun logOut(timeoutMillis: Long = 3000): Boolean {
        val connection = server.connection ?: return true
        return connection.logoutAndAwaitClose(timeoutMillis)
    }

    // Frames and values, asked for as they are opened.

    internal fun computeChildren(suspension: Suspension, frame: XsbugFrame, scope: Scope, path: List<XsbugTree.Step>, node: XCompositeNode) {
        requests.execute {
            try {
                if (this.suspension !== suspension) return@execute node.setErrorMessage("The app is running")
                var view = suspension.view(frame)
                while (true) {
                    val properties = scope.of(view) ?: return@execute node.setErrorMessage("Not available")
                    if (path.isEmpty()) return@execute node.addChildren(children(suspension, frame, scope, path, properties), true)
                    when (val lookup = XsbugTree.find(properties, path)) {
                        is XsbugTree.Lookup.Found -> {
                            node.addChildren(children(suspension, frame, scope, path, lookup.property.children), true)
                            return@execute
                        }
                        is XsbugTree.Lookup.Collapsed -> view = suspension.request(frame, XsbugCommands.toggle(lookup.id))
                        XsbugTree.Lookup.Gone -> return@execute node.setErrorMessage("No longer available")
                    }
                    if (this.suspension !== suspension) return@execute node.setErrorMessage("The app is running")
                }
            } catch (e: Exception) {
                node.setErrorMessage(e.message ?: "The app did not answer")
            }
        }
    }

    /** A frame's own variables, and its globals as a group. */
    internal fun computeFrameChildren(suspension: Suspension, frame: XsbugFrame, node: XCompositeNode) {
        requests.execute {
            try {
                if (this.suspension !== suspension) return@execute node.setErrorMessage("The app is running")
                val view = suspension.view(frame)
                val list = children(suspension, frame, Scope.LOCAL, emptyList(), view.local?.properties.orEmpty())
                list.addTopGroup(AlloyGlobals(this, suspension, frame))
                node.addChildren(list, true)
            } catch (e: Exception) {
                node.setErrorMessage(e.message ?: "The app did not answer")
            }
        }
    }

    private fun children(suspension: Suspension, frame: XsbugFrame, scope: Scope, path: List<XsbugTree.Step>, properties: List<XsbugProperty>) =
        XValueChildrenList().apply {
            for (property in properties) add(AlloyValue(this@AlloyDebugProcess, suspension, frame, scope, path, property))
        }

    private fun print(text: String, type: com.intellij.openapi.util.Key<*>) {
        result.processHandler.notifyTextAvailable(text, type)
    }

    /** Which part of a view a value is in. */
    enum class Scope {
        LOCAL {
            override fun of(view: XsbugMessage) = view.local?.properties
        },
        GLOBAL {
            override fun of(view: XsbugMessage) = view.global
        };

        abstract fun of(view: XsbugMessage): List<XsbugProperty>?
    }

    /**
     * The app stopped once: its stack, and the latest view of the frame selected in the engine.
     * Replaced as soon as it runs again, so anything asked of an old one is turned away.
     */
    internal class Suspension(val connection: XsbugConnection, val frames: List<XsbugFrame>, firstView: XsbugMessage) {
        @Volatile
        private var selected: String? = frames.firstOrNull()?.id

        @Volatile
        private var latest: XsbugMessage = firstView

        /** The view of [frame], selecting it in the engine first if another is selected. */
        fun view(frame: XsbugFrame): XsbugMessage =
            if (selected == frame.id) latest else request(frame, XsbugCommands.select(frame.id))

        fun request(frame: XsbugFrame, command: ByteArray): XsbugMessage {
            if (selected != frame.id && !command.contentEquals(XsbugCommands.select(frame.id))) view(frame)
            val view = connection.request(command).get(REPLY_SECONDS, TimeUnit.SECONDS)
            selected = view.local?.frame?.id ?: frame.id
            latest = view
            return view
        }
    }

    private object EditorsProvider : XDebuggerEditorsProvider() {
        override fun getFileType(): FileType = FileTypeManager.getInstance().getFileTypeByExtension("js")
    }

    companion object {
        private const val REPLY_SECONDS = 10L
    }
}
