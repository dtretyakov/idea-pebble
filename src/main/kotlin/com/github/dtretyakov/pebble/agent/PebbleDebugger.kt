package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.debug.BreakpointStates
import com.github.dtretyakov.pebble.run.PebbleRunConfiguration
import com.github.dtretyakov.pebble.run.PebbleRunConfigurationType
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.application.writeAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueContainer
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.nio.file.Path
import javax.swing.Icon

@Serializable
data class DebugState(
    /** `suspended`, `running`, `stopped`, or `none` when there is no Pebble debug session. */
    val state: String,
    val position: DebugPosition? = null,
    val frames: List<DebugFrame> = emptyList(),
    /** The selected frame's variables, objects and structs one level deep. */
    val variables: List<DebugValue> = emptyList(),
    /** What the run printed, when the debugger did not attach. */
    val output: List<String> = emptyList(),
)

@Serializable
data class DebugPosition(val file: String, val line: Int)

@Serializable
data class DebugFrame(val index: Int, val text: String, val file: String? = null, val line: Int? = null)

@Serializable
data class DebugValue(
    val name: String,
    val value: String,
    val type: String? = null,
    val children: List<DebugValue>? = null,
    /** Children there are, but not listed: ask for this value with `pebble_debug_evaluate`. */
    val expandable: Boolean = false,
)

@Serializable
data class BreakpointOutcome(
    val file: String,
    val line: Int,
    /** `verified`, `invalid`, `pending` (set when the debugger attaches) or `removed`. */
    val state: String,
    val message: String? = null,
)

/**
 * The IDE's debugger, for agents: a Pebble debug session — gdb for C, the JavaScript engine for
 * Alloy — started, given breakpoints, read, stepped and stopped through the platform's own
 * debugger API, so the session is the one in the Debug window and works the same for both.
 */
@Service(Service.Level.PROJECT)
class PebbleDebugger(private val project: Project) {

    private val agent get() = PebbleAgent.getInstance(project)

    /** The Pebble debug session — the selected one, or any — or else the session selected in the IDE. */
    fun session(): XDebugSession? {
        val manager = XDebuggerManager.getInstance(project)
        val pebble = manager.debugSessions.filter { !it.isStopped && it.runProfile is PebbleRunConfiguration }
        return pebble.firstOrNull { it === manager.currentSession } ?: pebble.firstOrNull() ?: manager.currentSession?.takeIf { !it.isStopped }
    }

    /**
     * A debug build installed and run under the debugger on [platform]'s emulator, as Debug does;
     * returns once the debugger is attached — gdb to the emulator, or the app's engine to the IDE.
     */
    suspend fun start(platform: String?, timeoutSeconds: Long = 300): DebugState {
        val root = agent.root()
        val chosen = agent.platform(root, platform)
        session()?.let { previous -> withContext(PebbleAgent.ui) { previous.stop() } }
        val build = agent.build(debug = true)
        if (!build.succeeded) {
            throw AgentProblem("The debug build failed: " + (build.problem ?: build.errors.take(3).joinToString("; ") { "${it.file}:${it.line}: ${it.message}" }))
        }
        val settings = withContext(PebbleAgent.ui) {
            val type = ConfigurationTypeUtil.findConfigurationType(PebbleRunConfigurationType::class.java)
            RunManager.getInstance(project).createConfiguration("Debug on $chosen", type.configurationFactories.first()).also {
                val configuration = it.configuration as PebbleRunConfiguration
                configuration.options.target = PebbleRunTarget.EMULATOR
                configuration.options.platform = chosen
                configuration.options.logs = true
                configuration.beforeRunTasks = emptyList()
            }
        }
        val execution = agent.execute(settings, timeoutSeconds, DefaultDebugExecutor.getDebugExecutorInstance()) { output ->
            ATTACHED.any { it in output } || FAILED.any { it in output }
        }
        val output = execution.output.lines().map { ANSI.replace(it, "").trimEnd() }.filter { it.isNotBlank() }
        if (!execution.matched || FAILED.any { it in execution.output }) {
            return DebugState(if (session() == null) "none" else "running", output = output.takeLast(30))
        }
        return state()
    }

    /** Sets, changes or removes a line breakpoint; [line] is one-based. */
    suspend fun breakpoint(file: String, line: Int, condition: String?, log: String?, remove: Boolean): BreakpointOutcome {
        val root = agent.root()
        val path = Path.of(file).let { if (it.isAbsolute) it else root.resolve(it) }.normalize()
        val virtual = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: throw AgentProblem("No file at $file.")
        val shown = agent.relative(root, path)
        val index = line - 1
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val types = XDebuggerUtil.getInstance().lineBreakpointTypes
        val existing = manager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>().filter { it.fileUrl == virtual.url && it.line == index }
        if (remove) {
            writeAction { existing.forEach(manager::removeBreakpoint) }
            return BreakpointOutcome(shown, line, "removed")
        }
        val breakpoint = existing.firstOrNull() ?: run {
            val type = com.intellij.openapi.application.readAction { types.firstOrNull { it.canPutAt(virtual, index, project) } }
                ?: throw AgentProblem("No breakpoint can be set at $shown:$line: a line with code in the app's own sources, please.")
            writeAction {
                @Suppress("UNCHECKED_CAST")
                val typed = type as com.intellij.xdebugger.breakpoints.XLineBreakpointType<com.intellij.xdebugger.breakpoints.XBreakpointProperties<*>>
                manager.addLineBreakpoint(typed, virtual.url, index, typed.createBreakpointProperties(virtual, index))
            }
        }
        writeAction {
            val util = XDebuggerUtil.getInstance()
            breakpoint.conditionExpression = condition?.takeIf { it.isNotBlank() }?.let { util.createExpression(it, null, null, EvaluationMode.EXPRESSION) }
            breakpoint.logExpressionObject = log?.takeIf { it.isNotBlank() }?.let { util.createExpression(it, null, null, EvaluationMode.EXPRESSION) }
        }
        if (session() == null) return BreakpointOutcome(shown, line, "pending", "Set when the debugger attaches: pebble_debug_start.")
        val states = BreakpointStates.getInstance(project)
        val state = withTimeoutOrNull(10_000) {
            while (states.of(breakpoint) == null) delay(100)
            states.of(breakpoint)
        } ?: return BreakpointOutcome(shown, line, "pending", "The debugger has not answered for it yet.")
        return BreakpointOutcome(shown, line, if (state.verified) "verified" else "invalid", state.message)
    }

    /** Waits until the app stops — at a breakpoint, a step's end, a crash — or [timeoutSeconds] pass. */
    suspend fun await(timeoutSeconds: Long): DebugState {
        val session = session() ?: return DebugState("none")
        if (session.isSuspended || session.isStopped) return state()
        val stopped = CompletableDeferred<Unit>()
        val listener = object : XDebugSessionListener {
            override fun sessionPaused() {
                stopped.complete(Unit)
            }

            override fun sessionStopped() {
                stopped.complete(Unit)
            }
        }
        session.addSessionListener(listener)
        try {
            if (!session.isSuspended && !session.isStopped) withTimeoutOrNull(timeoutSeconds * 1000) { stopped.await() }
        } finally {
            session.removeSessionListener(listener)
        }
        return state()
    }

    /** Steps, resumes or pauses; then, unless told not to, waits for the next stop. */
    suspend fun control(action: String, wait: Boolean, timeoutSeconds: Long): DebugState {
        val session = session() ?: throw AgentProblem("No Pebble debug session: pebble_debug_start.")
        val suspendedOnly = action in setOf("over", "into", "out", "resume")
        if (suspendedOnly && !session.isSuspended) throw AgentProblem("The app is running: '$action' needs it stopped. pebble_debug_wait, or 'pause'.")
        withContext(PebbleAgent.ui) {
            when (action) {
                "over" -> session.stepOver(false)
                "into" -> session.stepInto()
                "out" -> session.stepOut()
                "resume" -> session.resume()
                "pause" -> session.pause()
                else -> throw AgentProblem("Actions: over, into, out, resume, pause.")
            }
        }
        if (!wait || action == "resume") return DebugState(if (session.isStopped) "stopped" else "running")
        // The session reports itself running before it stops again.
        withTimeoutOrNull(2000) { while (session.isSuspended && !session.isStopped) delay(20) }
        return await(timeoutSeconds)
    }

    /** Evaluates [expression] in a frame of the stopped app: its value, and its fields or elements. */
    suspend fun evaluate(expression: String, frame: Int): DebugValue {
        val session = session() ?: throw AgentProblem("No Pebble debug session: pebble_debug_start.")
        if (!session.isSuspended) throw AgentProblem("The app is running; evaluating needs it stopped.")
        val frames = frames(session)
        val target = frames.getOrNull(frame) ?: throw AgentProblem("No frame $frame; there are ${frames.size}.")
        val evaluator = target.evaluator ?: throw AgentProblem("This frame cannot evaluate expressions.")
        val result = CompletableDeferred<Result<XValue>>()
        evaluator.evaluate(
            expression,
            object : XDebuggerEvaluator.XEvaluationCallback {
                override fun evaluated(value: XValue) {
                    result.complete(Result.success(value))
                }

                override fun errorOccurred(errorMessage: String) {
                    result.complete(Result.failure(AgentProblem(errorMessage)))
                }
            },
            session.currentPosition,
        )
        val value = withTimeoutOrNull(TIMEOUT) { result.await() }?.getOrThrow() ?: throw AgentProblem("The debugger did not answer.")
        return describe(expression, value, depth = 1)
    }

    suspend fun stop(): DebugState {
        val session = session() ?: return DebugState("none")
        withContext(PebbleAgent.ui) { session.stop() }
        return DebugState("stopped")
    }

    /** The session now: where it stopped, its stack and the selected frame's variables. */
    suspend fun state(): DebugState {
        val session = session() ?: return DebugState("none")
        if (session.isStopped) return DebugState("stopped")
        if (!session.isSuspended) return DebugState("running")
        val root = agent.root()
        val position = session.currentPosition?.let { position ->
            position.file.toNioPathOrNull()?.let { DebugPosition(agent.relative(root, it), position.line + 1) }
        }
        val frames = frames(session)
        val described = frames.mapIndexed { index, frame ->
            val where = frame.sourcePosition
            DebugFrame(index, text(frame), where?.file?.toNioPathOrNull()?.let { agent.relative(root, it) }, where?.line?.plus(1))
        }
        val selected = session.currentStackFrame ?: frames.firstOrNull()
        val variables = selected?.let { children(it).map { (name, value) -> describe(name, value, depth = 1) } }.orEmpty()
        return DebugState("suspended", position, described, variables)
    }

    private suspend fun frames(session: XDebugSession): List<XStackFrame> {
        val stack = session.suspendContext?.activeExecutionStack ?: return emptyList()
        return frames(stack)
    }

    private suspend fun frames(stack: XExecutionStack): List<XStackFrame> {
        val frames = mutableListOf<XStackFrame>()
        val done = CompletableDeferred<Unit>()
        stack.computeStackFrames(
            0,
            object : XExecutionStack.XStackFrameContainer {
                override fun addStackFrames(stackFrames: MutableList<out XStackFrame>, last: Boolean) {
                    synchronized(frames) { frames += stackFrames }
                    if (last || frames.size >= MAX_FRAMES) done.complete(Unit)
                }

                override fun errorOccurred(errorMessage: String) {
                    done.complete(Unit)
                }

                override fun isObsolete(): Boolean = done.isCompleted
            },
        )
        withTimeoutOrNull(TIMEOUT) { done.await() }
        return synchronized(frames) { frames.take(MAX_FRAMES) }
    }

    private suspend fun children(container: XValueContainer): List<Pair<String, XValue>> {
        val children = mutableListOf<Pair<String, XValue>>()
        val done = CompletableDeferred<Unit>()
        container.computeChildren(
            object : XCompositeNode {
                override fun addChildren(list: XValueChildrenList, last: Boolean) {
                    synchronized(children) { for (i in 0 until list.size()) children += list.getName(i) to list.getValue(i) }
                    if (last) done.complete(Unit)
                }

                override fun tooManyChildren(remaining: Int) {
                    done.complete(Unit)
                }

                override fun setAlreadySorted(alreadySorted: Boolean) = Unit

                override fun setErrorMessage(errorMessage: String) {
                    done.complete(Unit)
                }

                override fun setErrorMessage(errorMessage: String, link: XDebuggerTreeNodeHyperlink?) {
                    done.complete(Unit)
                }

                override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: XDebuggerTreeNodeHyperlink?) = Unit

                override fun isObsolete(): Boolean = done.isCompleted
            },
        )
        withTimeoutOrNull(TIMEOUT) { done.await() }
        return synchronized(children) { children.take(MAX_CHILDREN) }
    }

    private suspend fun describe(name: String, value: XValue, depth: Int): DebugValue {
        val presented = CompletableDeferred<Triple<String, String?, Boolean>>()
        value.computePresentation(
            object : XValueNode {
                override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                    presented.complete(Triple(value, type, hasChildren))
                }

                override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                    presented.complete(Triple(render(presentation), presentation.type, hasChildren))
                }

                override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) = Unit

                override fun isObsolete(): Boolean = presented.isCompleted
            },
            XValuePlace.TREE,
        )
        val (text, type, hasChildren) = withTimeoutOrNull(TIMEOUT) { presented.await() } ?: Triple("(no answer)", null, false)
        if (!hasChildren) return DebugValue(name, text, type)
        if (depth <= 0) return DebugValue(name, text, type, expandable = true)
        val children = children(value).map { (childName, child) -> describe(childName, child, depth - 1) }
        return DebugValue(name, text, type, children)
    }

    private fun render(presentation: XValuePresentation): String {
        val text = StringBuilder()
        presentation.renderValue(
            object : XValuePresentation.XValueTextRenderer {
                override fun renderValue(value: String) {
                    text.append(value)
                }

                override fun renderValue(value: String, key: TextAttributesKey) {
                    text.append(value)
                }

                override fun renderStringValue(value: String) {
                    text.append('"').append(value).append('"')
                }

                override fun renderStringValue(value: String, additionalSpecialCharsToHighlight: String?, maxLength: Int) {
                    text.append('"').append(value.take(maxLength)).append('"')
                }

                override fun renderNumericValue(value: String) {
                    text.append(value)
                }

                override fun renderKeywordValue(value: String) {
                    text.append(value)
                }

                override fun renderComment(comment: String) {
                    text.append(' ').append(comment)
                }

                override fun renderSpecialSymbol(symbol: String) {
                    text.append(symbol)
                }

                override fun renderError(error: String) {
                    text.append(error)
                }
            },
        )
        return text.toString()
    }

    private fun text(frame: XStackFrame): String {
        val text = StringBuilder()
        frame.customizePresentation(
            object : ColoredTextContainer {
                override fun append(fragment: String, attributes: SimpleTextAttributes) {
                    text.append(fragment)
                }

                override fun setIcon(icon: Icon?) = Unit

                override fun setToolTipText(text: String?) = Unit
            },
        )
        return text.toString().trim()
    }

    companion object {
        /** What the Pebble debuggers print once attached: gdb to the emulator, or the app's engine to the IDE. */
        private val ATTACHED = listOf("gdb is attached", "is connected to the debugger")
        private val FAILED = listOf("The debugger cannot", "gdb could not attach")
        private const val TIMEOUT = 15_000L
        private const val MAX_FRAMES = 30
        private const val MAX_CHILDREN = 50
        private val ANSI = Regex("\u001b\\[[0-9;?]*[A-Za-z]")

        fun getInstance(project: Project): PebbleDebugger = project.service()
    }
}
