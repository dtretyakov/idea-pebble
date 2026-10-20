package com.github.dtretyakov.pebble.debug.gdb

import com.github.dtretyakov.pebble.debug.gdb.GdbConnection.GdbError
import com.github.dtretyakov.pebble.debug.invalidate
import com.github.dtretyakov.pebble.debug.verify
import com.intellij.execution.ExecutionResult
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ExecutionConsole
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueModifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.invariantSeparatorsPathString

/**
 * A C app in the IDE's debugger: gdb in MI mode, attached to the emulator as `pebble gdb` attaches
 * it ([GdbSetup]).
 *
 * The run's process is `pebble install … --logs`, and its console is the run's own. Once
 * pebble-tool says the app is installed — and so started, at the address gdb is told to find it
 * at — gdb attaches, is given the breakpoints, and lets the app run on.
 *
 * By then the app's start — `main`, `init`, the first window's load — has run, and a breakpoint
 * there would never be reached. So when there are breakpoints, the app is installed once more
 * ([relaunch]) with gdb attached: the firmware loads it at the same address every time, gdb's
 * breakpoints still stand, and the start runs under the debugger.
 *
 * Everything sent to gdb goes through one queue, so a command never interleaves with another. gdb
 * cannot change breakpoints while the app runs, so a breakpoint set then interrupts the app, is
 * set, and lets it go on; that stop is the debugger's own and not shown.
 */
class GdbDebugProcess(
    session: XDebugSession,
    private val result: ExecutionResult,
    private val setup: () -> GdbSetup,
    /** Starts the app again, blocking; what went wrong, or null. None: the start is not rerun. */
    private val relaunch: (() -> String?)? = null,
    private val connect: (GdbSetup, GdbConnection.Listener) -> GdbConnection = GdbConnection::launch,
) : XDebugProcess(session), GdbConnection.Listener {

    private val registered = ConcurrentHashMap.newKeySet<XLineBreakpoint<XBreakpointProperties<*>>>()
    private val numbers = ConcurrentHashMap<XLineBreakpoint<XBreakpointProperties<*>>, String>()

    @Volatile
    private var connection: GdbConnection? = null

    /** What gdb was started with: how it spells paths, which under WSL is not how the IDE does. */
    @Volatile
    private var prepared: GdbSetup? = null

    @Volatile
    private var attaching = false

    @Volatile
    private var stopping = false

    @Volatile
    private var stepping = false

    @Volatile
    private var crashBreakpoint: String? = null

    /** Completed with the next stop, when the debugger stops the app for itself. */
    @Volatile
    private var ownStop: CompletableFuture<MiRecord>? = null

    @Volatile
    internal var suspension: Suspension? = null
        private set

    private val attached = AtomicBoolean()

    private val commands = AppExecutorUtil.createBoundedApplicationPoolExecutor("gdb commands", 1)

    init {
        session.setPauseActionSupported(true)
        result.processHandler.addProcessListener(
            object : ProcessListener {
                private val seen = StringBuilder()

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (attached.get()) return
                    val installed = synchronized(seen) {
                        seen.append(event.text)
                        val found = INSTALLED in seen
                        if (seen.length > 4096) seen.delete(0, seen.length - INSTALLED.length)
                        found
                    }
                    if (installed) attach()
                }
            },
        )
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = CExpressionEditorsProvider

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = listOfNotNull(
        GdbLineBreakpointHandler(PebbleCLineBreakpointType::class.java, this),
        GdbBreakpoints.clionLineType()?.let { GdbLineBreakpointHandler(GdbLineBreakpointHandler.forType(it), this) },
    ).toTypedArray()

    override fun doGetProcessHandler(): ProcessHandler = result.processHandler

    override fun createConsole(): ExecutionConsole = result.executionConsole

    // Attaching, once the app is installed and running.

    internal fun attach() {
        if (!attached.compareAndSet(false, true)) return
        commands.execute {
            if (stopping) return@execute
            val (config, gdb) = try {
                setup().let { it to connect(it, this) }
            } catch (e: GdbSetup.Problem) {
                return@execute print("The debugger cannot attach: ${e.message}\n", ProcessOutputTypes.STDERR)
            } catch (e: Exception) {
                return@execute print("The debugger cannot start gdb: ${e.message}\n", ProcessOutputTypes.STDERR)
            }
            connection = gdb
            prepared = config
            attaching = true
            try {
                for (command in config.commands()) {
                    val answer = gdb.call(command)
                    if (command.endsWith(GdbSetup.CRASH_FUNCTION)) crashBreakpoint = answer.tuple("bkpt")?.text("number")
                }
                attaching = false
                registered.forEach { insert(gdb, it) }
                gdb.call(GdbCommands.CONTINUE)
                // One line with what follows, so a reader of the console — an agent waiting on it —
                // knows from the first whether to wait for the app's second start too.
                val again = numbers.isNotEmpty() && relaunch != null
                print("$ATTACHED${if (again) " $RELAUNCHING" else ""}\n", ProcessOutputTypes.SYSTEM)
                if (again) startAgain()
            } catch (e: GdbError) {
                print("gdb could not attach to the emulator: ${e.message}\n", ProcessOutputTypes.STDERR)
                connection = null
                gdb.close()
            } finally {
                attaching = false
            }
        }
    }

    /**
     * Runs the app's start again under gdb. Off the command queue: the install takes seconds,
     * and a breakpoint it reaches is shown, and stepped from, through that queue meanwhile. Only
     * with breakpoints set — without one the second start would only show up twice in the log.
     */
    private fun startAgain() {
        val relaunch = relaunch ?: return
        AppExecutorUtil.getAppExecutorService().execute {
            if (stopping) return@execute
            when (val problem = relaunch()) {
                null -> print("$RELAUNCHED\n", ProcessOutputTypes.SYSTEM)
                else -> print("$NOT_RELAUNCHED $problem\n", ProcessOutputTypes.STDERR)
            }
        }
    }

    // Breakpoints, kept for the attach and set at once once attached.

    internal fun addLineBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        if (breakpoint.sourcePosition?.file?.toNioPathOrNull() == null) return
        registered += breakpoint
        commands.execute {
            val gdb = connection?.takeIf { !attaching && !it.isClosed } ?: return@execute
            insert(gdb, breakpoint)
        }
    }

    internal fun removeLineBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        registered -= breakpoint
        commands.execute {
            val number = numbers.remove(breakpoint) ?: return@execute
            val gdb = connection?.takeIf { !it.isClosed } ?: return@execute
            try {
                whileStopped(gdb) { gdb.call(GdbCommands.breakDelete(number)) }
            } catch (_: GdbError) {
                // gdb is going, or the app is; the breakpoint goes with it.
            }
        }
    }

    /** Sets [breakpoint] in gdb; called on the command queue. */
    private fun insert(gdb: GdbConnection, breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) {
        if (breakpoint !in registered || numbers.containsKey(breakpoint)) return
        val path = breakpoint.sourcePosition?.file?.toNioPathOrNull() ?: return
        val command = GdbCommands.breakInsert((prepared?.spell ?: GdbSetup.DEFAULT_SPELL)(path), breakpoint.line + 1, breakpoint.conditionExpression?.expression)
        val answer = try {
            whileStopped(gdb) { gdb.call(command) }
        } catch (e: GdbError) {
            return session.invalidate(breakpoint, e.message)
        }
        val number = answer.tuple("bkpt")?.text("number") ?: return session.invalidate(breakpoint, "gdb set no breakpoint")
        numbers[breakpoint] = number
        session.verify(breakpoint)
    }

    /**
     * Runs [block] with the app stopped: as it is, or interrupted for it and let go after. If the
     * app stopped by itself meanwhile — a breakpoint hit as it was interrupted — that stop is shown.
     */
    private fun <T> whileStopped(gdb: GdbConnection, block: () -> T): T {
        if (!gdb.running) return block()
        val stop = interrupt(gdb) ?: return block()
        try {
            return block()
        } finally {
            if (stop.text("reason") == "signal-received" && !stopping) gdb.call(GdbCommands.CONTINUE) else if (!stopping) stopped(stop)
        }
    }

    /** Interrupts the running app and waits for it to stop; the stop is not shown. */
    private fun interrupt(gdb: GdbConnection): MiRecord? {
        val stop = CompletableFuture<MiRecord>()
        ownStop = stop
        try {
            gdb.call(GdbCommands.INTERRUPT)
            return stop.get(GdbConnection.TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw GdbError("The app did not stop when interrupted")
        } finally {
            ownStop = null
        }
    }

    // What gdb says.

    override fun record(record: MiRecord) {
        when {
            record.kind == MiRecord.Kind.EXEC && record.type == "stopped" -> {
                if (ownStop?.complete(record) == true || attaching || stopping) return
                commands.execute { stopped(record) }
            }
            record.kind == MiRecord.Kind.TARGET -> print(record.text.orEmpty(), ProcessOutputTypes.STDOUT)
        }
    }

    override fun closed() {
        val wasSuspended = suspension != null
        suspension = null
        connection = null
        if (stopping) return
        if (wasSuspended) session.sessionResumed()
        print("gdb has exited; the app runs on without the debugger.\n", ProcessOutputTypes.SYSTEM)
    }

    /** The app stopped; called on the command queue. */
    private fun stopped(record: MiRecord) {
        val gdb = connection ?: return
        val thread = record.text("thread-id") ?: "1"
        val frames = try {
            GdbFrame.list(gdb.call(GdbCommands.frames(thread)))
        } catch (_: GdbError) {
            listOfNotNull(record.tuple("frame")?.let(GdbFrame::of))
        }
        val reason = record.text("reason")
        if (stepping && reason in STEP_ENDS) {
            try {
                when (val next = GdbLanding.after(frames, ::isAppSource)) {
                    GdbLanding.Next.Stay -> Unit
                    is GdbLanding.Next.Finish -> return run { gdb.call(GdbCommands.finish(thread, next.frame)) }
                    GdbLanding.Next.Continue -> return run {
                        stepping = false
                        gdb.call(GdbCommands.CONTINUE)
                    }
                }
            } catch (_: GdbError) {
                // Shown where it stopped, then.
            }
        }
        stepping = false
        val suspension = Suspension(thread, frames)
        this.suspension = suspension
        val context = GdbSuspendContext(this, suspension)
        val number = record.text("bkptno")
        val breakpoint = numbers.entries.firstOrNull { it.value == number }?.key.takeIf { reason == "breakpoint-hit" }
        when {
            reason == "breakpoint-hit" && number != null && number == crashBreakpoint -> {
                print("The app crashed: the firmware stopped it in ${GdbSetup.CRASH_FUNCTION}. The stack shows where.\n", ProcessOutputTypes.STDERR)
                session.positionReached(context)
            }
            breakpoint != null -> {
                val logged = breakpoint.logExpressionObject?.expression?.takeIf { it.isNotBlank() }?.let { evaluateText(gdb, suspension, it) }
                if (!session.breakpointReached(breakpoint, logged, context)) resume(context)
            }
            else -> session.positionReached(context)
        }
    }

    // Running.

    override fun resume(context: XSuspendContext?) = proceed(step = false) { GdbCommands.CONTINUE }

    override fun startStepOver(context: XSuspendContext?) = proceed(step = true) { GdbCommands.NEXT }

    override fun startStepInto(context: XSuspendContext?) = proceed(step = true) { GdbCommands.STEP }

    override fun startStepOut(context: XSuspendContext?) = proceed(step = true) { GdbCommands.finish(it?.thread ?: "1", 0) }

    override fun startPausing() {
        stepping = false
        connection?.request(GdbCommands.INTERRUPT)
    }

    private fun proceed(step: Boolean, command: (Suspension?) -> String) {
        val current = suspension
        suspension = null
        commands.execute {
            val gdb = connection ?: return@execute
            current?.let { forget(gdb, it) }
            stepping = step
            try {
                gdb.call(command(current))
            } catch (e: GdbError) {
                stepping = false
                print("${e.message}\n", ProcessOutputTypes.STDERR)
                if (current != null && !gdb.running) {
                    suspension = current
                    session.positionReached(GdbSuspendContext(this, current))
                }
            }
        }
    }

    /** The variable objects made while the app was stopped, deleted as it goes on. */
    private fun forget(gdb: GdbConnection, suspension: Suspension) {
        while (true) {
            val name = suspension.variables.poll() ?: break
            runCatching { gdb.call(GdbCommands.varDelete(name)) }
        }
    }

    /**
     * Detaches, so the app runs on as if never debugged, and lets gdb go: called before the run's
     * process is stopped. False if gdb did not manage in [timeoutMillis].
     */
    fun detach(timeoutMillis: Long = 5000): Boolean {
        stopping = true
        val gdb = connection ?: return true
        val done = CompletableFuture.runAsync({ detachNow(gdb) }, commands)
        return runCatching { done.get(timeoutMillis, TimeUnit.MILLISECONDS) }.isSuccess.also { if (!it) gdb.close() }
    }

    private fun detachNow(gdb: GdbConnection) {
        try {
            if (!gdb.isClosed) {
                if (gdb.running) interrupt(gdb)
                gdb.call(GdbCommands.DETACH)
                gdb.request(GdbCommands.EXIT)
            }
        } catch (_: GdbError) {
            // Going anyway.
        } finally {
            gdb.close()
        }
    }

    override fun stop() {
        stopping = true
        val gdb = connection ?: return
        commands.execute { detachNow(gdb) }
    }

    // Frames, values and expressions, asked for as they are opened.

    internal fun isAppSource(frame: GdbFrame): Boolean {
        val path = sourceOf(frame) ?: return false
        return path.isAbsolute && Files.isRegularFile(path)
    }

    /** The source a frame names, as the IDE reaches it. */
    internal fun sourceOf(frame: GdbFrame): Path? = frame.fullname?.let(prepared?.unspell ?: GdbSetup.DEFAULT_UNSPELL)

    internal fun computeFrameChildren(suspension: Suspension, frame: GdbFrame, node: XCompositeNode) = onStopped(suspension, node::setErrorMessage) { gdb ->
        val names = gdb.call(GdbCommands.variables(suspension.thread, frame.level)).list("variables")
            .filterIsInstance<MiValue.Tuple>().mapNotNull { it.text("name") }.distinct()
        val list = XValueChildrenList()
        for (name in names) list.add(GdbValue(this, suspension, variable(gdb, suspension, frame, name), name))
        node.addChildren(list, true)
    }

    internal fun computeChildren(suspension: Suspension, parent: GdbVariable, node: XCompositeNode) = onStopped(suspension, node::setErrorMessage) { gdb ->
        val children = gdb.call(GdbCommands.varChildren(parent.name, MAX_CHILDREN)).list("children")
            .filterIsInstance<MiValue.Tuple>().map(GdbVariable::of)
        val list = XValueChildrenList()
        for (child in children) list.add(GdbValue(this, suspension, child, child.displayName(parent)))
        node.addChildren(list, true)
        if (parent.children > MAX_CHILDREN) node.tooManyChildren(parent.children - MAX_CHILDREN)
    }

    internal fun evaluate(suspension: Suspension, frame: GdbFrame, expression: String, callback: XDebuggerEvaluator.XEvaluationCallback) =
        onStopped(suspension, callback::errorOccurred) { gdb ->
            callback.evaluated(GdbValue(this, suspension, variable(gdb, suspension, frame, expression), expression))
        }

    internal fun assign(suspension: Suspension, variable: GdbVariable, expression: String, callback: XValueModifier.XModificationCallback) =
        onStopped(suspension, callback::errorOccurred) { gdb ->
            gdb.call(GdbCommands.varAssign(variable.name, expression))
            callback.valueModified()
        }

    /** A logged expression's value as text; called on the command queue. */
    private fun evaluateText(gdb: GdbConnection, suspension: Suspension, expression: String): String = try {
        gdb.call(GdbCommands.evaluate(suspension.thread, suspension.frames.firstOrNull()?.level ?: 0, expression)).text("value").orEmpty()
    } catch (e: GdbError) {
        "$expression: ${e.message}"
    }

    private fun variable(gdb: GdbConnection, suspension: Suspension, frame: GdbFrame, expression: String): GdbVariable {
        val created = GdbVariable.of(gdb.call(GdbCommands.varCreate(suspension.thread, frame.level, expression)).results)
        suspension.variables += created.name
        return created
    }

    private fun onStopped(suspension: Suspension, error: (String) -> Unit, block: (GdbConnection) -> Unit) {
        commands.execute {
            val gdb = connection
            if (gdb == null || this.suspension !== suspension) return@execute error("The app is running")
            try {
                block(gdb)
            } catch (e: GdbError) {
                error(e.message ?: "gdb could not do it")
            }
        }
    }

    private fun print(text: String, type: Key<*>) {
        result.processHandler.notifyTextAvailable(text, type)
    }

    /**
     * The app stopped once: its stack, and the variable objects made for it. Replaced as soon as
     * it runs again, so anything asked of an old one is turned away.
     */
    internal class Suspension(val thread: String, val frames: List<GdbFrame>) {
        val variables = ConcurrentLinkedQueue<String>()
    }

    companion object {
        /** What pebble-tool prints once the app is installed, and the watch has started it. */
        const val INSTALLED = "App install succeeded."

        /** The console's words for the attach and the second start, which the agent's tools wait on. */
        const val ATTACHED = "gdb is attached to the app in the emulator."
        const val RELAUNCHING = "Starting the app again, so its start runs under the debugger."
        const val RELAUNCHED = "The app has started again, under the debugger."
        const val NOT_RELAUNCHED = "The app could not be started again:"

        const val MAX_CHILDREN = 100

        private val STEP_ENDS = setOf("end-stepping-range", "function-finished")
    }
}
