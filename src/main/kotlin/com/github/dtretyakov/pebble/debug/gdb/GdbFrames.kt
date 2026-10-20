package com.github.dtretyakov.pebble.debug.gdb

import com.intellij.icons.AllIcons
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XNamedValue
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import java.nio.file.Path

/** The app stopped: the emulator's one stack, as gdb sees it through QEMU. */
internal class GdbSuspendContext(process: GdbDebugProcess, suspension: GdbDebugProcess.Suspension) : XSuspendContext() {

    private val stack = GdbExecutionStack(process, suspension)

    override fun getActiveExecutionStack(): XExecutionStack = stack

    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(stack)
}

internal class GdbExecutionStack(process: GdbDebugProcess, suspension: GdbDebugProcess.Suspension) : XExecutionStack("App") {

    private val frames = suspension.frames.map { GdbStackFrame(process, suspension, it) }

    override fun getTopFrame(): XStackFrame? = frames.firstOrNull()

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        container.addStackFrames(frames.drop(firstFrameIndex), true)
    }
}

/** A frame; the firmware's have no sources on this disk, and show greyed with gdb's file name. */
internal class GdbStackFrame(
    private val process: GdbDebugProcess,
    private val suspension: GdbDebugProcess.Suspension,
    private val frame: GdbFrame,
) : XStackFrame() {

    private val position: XSourcePosition? by lazy {
        if (!process.isAppSource(frame)) return@lazy null
        val file = process.sourceOf(frame)?.let(LocalFileSystem.getInstance()::findFileByNioFile) ?: return@lazy null
        XDebuggerUtil.getInstance().createPosition(file, (frame.line ?: 1) - 1)
    }

    override fun getSourcePosition(): XSourcePosition? = position

    override fun getEqualityObject(): Any = frame.level to frame.function

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(frame.function, if (position != null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val file = frame.file ?: frame.address ?: return
        component.append(" ${file.substringAfterLast('/')}${frame.line?.let { ":$it" }.orEmpty()}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) = process.computeFrameChildren(suspension, frame, node)

    override fun getEvaluator(): XDebuggerEvaluator = GdbEvaluator(process, suspension, frame)
}

/** Evaluates C in a frame, for Evaluate Expression, watches and hovering. */
internal class GdbEvaluator(
    private val process: GdbDebugProcess,
    private val suspension: GdbDebugProcess.Suspension,
    private val frame: GdbFrame,
) : XDebuggerEvaluator() {

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        process.evaluate(suspension, frame, expression, callback)
}

/** A gdb variable object: `name` is gdb's handle for it, `expression` what it shows. */
internal data class GdbVariable(val name: String, val expression: String, val children: Int, val value: String?, val type: String?) {

    /** An array's elements by index, a pointer's target and a struct's fields by their own names. */
    fun displayName(parent: GdbVariable): String =
        if (parent.type?.trimEnd()?.endsWith("]") == true && expression.all { it.isDigit() }) "[$expression]" else expression

    companion object {
        fun of(tuple: MiValue.Tuple): GdbVariable = GdbVariable(
            name = tuple.text("name").orEmpty(),
            expression = tuple.text("exp") ?: tuple.text("name").orEmpty(),
            children = tuple.text("numchild")?.toIntOrNull() ?: 0,
            value = tuple.text("value"),
            type = tuple.text("type"),
        )
    }
}

internal class GdbValue(
    private val process: GdbDebugProcess,
    private val suspension: GdbDebugProcess.Suspension,
    private val variable: GdbVariable,
    name: String,
) : XNamedValue(name) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) {
        node.setPresentation(AllIcons.Nodes.Variable, XRegularValuePresentation(variable.value.orEmpty(), variable.type), variable.children > 0)
    }

    override fun computeChildren(node: XCompositeNode) = process.computeChildren(suspension, variable, node)

    override fun getModifier(): XValueModifier? = if (variable.children > 0 && variable.value == "{...}") null else Modifier()

    private inner class Modifier : XValueModifier() {
        override fun getInitialValueEditorText(): String? = variable.value?.substringBefore(" <")

        override fun setValue(expression: com.intellij.xdebugger.XExpression, callback: XModificationCallback) =
            process.assign(suspension, variable, expression.expression, callback)
    }
}
