package com.github.dtretyakov.pebble.debug

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
import com.intellij.xdebugger.frame.XNavigatable
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueGroup
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XKeywordValuePresentation
import com.intellij.xdebugger.frame.presentation.XNumericValuePresentation
import com.intellij.xdebugger.frame.presentation.XRegularValuePresentation
import com.intellij.xdebugger.frame.presentation.XStringValuePresentation
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import java.nio.file.Path

/** The app stopped: one stack — the engine runs one thread — and its frames. */
internal class AlloySuspendContext(process: AlloyDebugProcess, suspension: AlloyDebugProcess.Suspension) : XSuspendContext() {

    private val stack = AlloyExecutionStack(process, suspension)

    override fun getActiveExecutionStack(): XExecutionStack = stack

    override fun getExecutionStacks(): Array<XExecutionStack> = arrayOf(stack)
}

internal class AlloyExecutionStack(process: AlloyDebugProcess, suspension: AlloyDebugProcess.Suspension) : XExecutionStack("JavaScript") {

    private val frames = suspension.frames.map { AlloyStackFrame(process, suspension, it) }

    override fun getTopFrame(): XStackFrame? = frames.firstOrNull()

    override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
        container.addStackFrames(frames.drop(firstFrameIndex), true)
    }
}

/** A frame; the engine's own (`(host)`) have no source. */
internal class AlloyStackFrame(
    private val process: AlloyDebugProcess,
    private val suspension: AlloyDebugProcess.Suspension,
    private val frame: XsbugFrame,
) : XStackFrame() {

    private val position: XSourcePosition? by lazy { position(frame.path, frame.line) }

    override fun getSourcePosition(): XSourcePosition? = position

    override fun getEqualityObject(): Any = frame.id

    override fun customizePresentation(component: ColoredTextContainer) {
        component.append(frame.name, if (position != null) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAYED_ATTRIBUTES)
        val path = frame.path ?: return
        component.append(" ${Path.of(path).fileName}:${frame.line}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        component.setIcon(AllIcons.Debugger.Frame)
    }

    override fun computeChildren(node: XCompositeNode) = process.computeFrameChildren(suspension, frame, node)

    override fun getEvaluator(): XDebuggerEvaluator = AlloyEvaluator(process, suspension, frame)
}

/** Evaluates in a frame, for Evaluate Expression, watches and hovering. */
internal class AlloyEvaluator(
    private val process: AlloyDebugProcess,
    private val suspension: AlloyDebugProcess.Suspension,
    private val frame: XsbugFrame,
) : XDebuggerEvaluator() {

    override fun evaluate(expression: String, callback: XEvaluationCallback, expressionPosition: XSourcePosition?) =
        process.evaluate(suspension, frame, expression, callback)
}

/**
 * What an evaluation gave: the engine sends an object's properties along, one level deep, and
 * that level is what is shown — the value is not one of the frame's, so it cannot be expanded
 * further by toggling it there.
 */
internal class AlloyResultValue(name: String, private val property: XsbugProperty) : XNamedValue(name) {

    override fun computePresentation(node: XValueNode, place: XValuePlace) =
        present(node, property, hasChildren = property.children.isNotEmpty())

    override fun computeChildren(node: XCompositeNode) {
        node.addChildren(XValueChildrenList().apply { property.children.forEach { add(AlloyResultValue(it.name, it)) } }, true)
    }

    override fun canNavigateToSource(): Boolean = property.sourcePath != null

    override fun computeSourcePosition(navigatable: XNavigatable) {
        navigatable.setSourcePosition(position(property.sourcePath, property.line))
    }
}

/** A variable or property, opened by asking the engine to expand its object. */
internal class AlloyValue(
    private val process: AlloyDebugProcess,
    private val suspension: AlloyDebugProcess.Suspension,
    private val frame: XsbugFrame,
    private val scope: AlloyDebugProcess.Scope,
    parentPath: List<XsbugTree.Step>,
    private val property: XsbugProperty,
) : XNamedValue(property.name) {

    private val path: List<XsbugTree.Step>? = property.objectId?.let { parentPath + XsbugTree.Step(property.name, it) }

    override fun computePresentation(node: XValueNode, place: XValuePlace) = present(node, property, hasChildren = path != null)

    override fun computeChildren(node: XCompositeNode) {
        val path = path ?: return node.addChildren(XValueChildrenList.EMPTY, true)
        process.computeChildren(suspension, frame, scope, path, node)
    }

    override fun canNavigateToSource(): Boolean = property.sourcePath != null

    override fun computeSourcePosition(navigatable: XNavigatable) {
        navigatable.setSourcePosition(position(property.sourcePath, property.line))
    }
}

private fun present(node: XValueNode, property: XsbugProperty, hasChildren: Boolean) {
    val shown = XsbugValues.of(property)
    val icon = when (shown.kind) {
        XsbugValues.Kind.FUNCTION -> AllIcons.Nodes.Function
        XsbugValues.Kind.OBJECT -> AllIcons.Debugger.Value
        else -> AllIcons.Nodes.Variable
    }
    val presentation: XValuePresentation = when (shown.kind) {
        XsbugValues.Kind.STRING -> XStringValuePresentation(shown.text)
        XsbugValues.Kind.NUMBER -> XNumericValuePresentation(shown.text)
        XsbugValues.Kind.KEYWORD -> XKeywordValuePresentation(shown.text)
        else -> XRegularValuePresentation(shown.text, null)
    }
    node.setPresentation(icon, presentation, hasChildren)
}

/** The globals, under the frame's own variables. */
internal class AlloyGlobals(
    private val process: AlloyDebugProcess,
    private val suspension: AlloyDebugProcess.Suspension,
    private val frame: XsbugFrame,
) : XValueGroup("Globals") {

    override fun getIcon() = AllIcons.Debugger.Value

    override fun computeChildren(node: XCompositeNode) =
        process.computeChildren(suspension, frame, AlloyDebugProcess.Scope.GLOBAL, emptyList(), node)
}

private fun position(path: String?, line: Int?): XSourcePosition? {
    if (path == null || line == null) return null
    val file = LocalFileSystem.getInstance().findFileByPath(path) ?: return null
    return XDebuggerUtil.getInstance().createPosition(file, line - 1)
}
