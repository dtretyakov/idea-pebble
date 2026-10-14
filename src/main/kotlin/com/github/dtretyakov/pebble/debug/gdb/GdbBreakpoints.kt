package com.github.dtretyakov.pebble.debug.gdb

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.UnknownFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * A line breakpoint in a C app's sources — for IDEs without CLion's debugger.
 *
 * In CLion its own C/C++ line breakpoints are the ones a click in the gutter sets, and the Pebble
 * debugger honours them; two types offered on one line would make every click ask which to set,
 * so this one stands aside there, as the Alloy type does for the JavaScript debugger's.
 */
class PebbleCLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(ID, "Pebble C Line Breakpoints") {

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    /** Conditions and logged expressions are C, evaluated by gdb. */
    override fun getEditorsProvider(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, project: Project): XDebuggerEditorsProvider =
        CExpressionEditorsProvider

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        offersOwnType(clionPresent = GdbBreakpoints.clionLineType() != null, cSource = GdbBreakpoints.isAppSource(project, file)) &&
            FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                line in 0 until document.lineCount &&
                    document.getText(TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).isNotBlank()
            } == true

    companion object {
        const val ID = "pebble-c-line"

        fun offersOwnType(clionPresent: Boolean, cSource: Boolean): Boolean = !clionPresent && cSource
    }
}

internal object GdbBreakpoints {

    /** CLion's C/C++ line breakpoint type, found by its id: no dependency on CLion's debugger. */
    fun clionLineType(): XLineBreakpointType<*>? =
        XBreakpointType.EXTENSION_POINT_NAME.extensionList.firstOrNull { it.id == CLION } as? XLineBreakpointType<*>

    /** A C source or header of a C app in this project: what gdb can set a line breakpoint in. */
    fun isAppSource(project: Project, file: VirtualFile): Boolean {
        if (file.extension?.lowercase() !in EXTENSIONS) return false
        val model = PebbleProject.getInstance(project)
        val root = model.rootFor(file) ?: return false
        return model.infoAt(root)?.projectType == PebbleProjectInfo.ProjectType.NATIVE
    }

    private const val CLION = "com.jetbrains.cidr.execution.debugger.OCBreakpointType"
    private val EXTENSIONS = setOf("c", "h")
}

/** Line breakpoints of one type — ours or CLion's — given to the gdb debugger. */
internal class GdbLineBreakpointHandler(
    type: Class<out XBreakpointType<XLineBreakpoint<XBreakpointProperties<*>>, *>>,
    private val process: GdbDebugProcess,
) : XBreakpointHandler<XLineBreakpoint<XBreakpointProperties<*>>>(type) {

    override fun registerBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>) = process.addLineBreakpoint(breakpoint)

    override fun unregisterBreakpoint(breakpoint: XLineBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) =
        process.removeLineBreakpoint(breakpoint)

    companion object {
        @Suppress("UNCHECKED_CAST")
        fun forType(type: XLineBreakpointType<*>): Class<out XBreakpointType<XLineBreakpoint<XBreakpointProperties<*>>, *>> =
            type.javaClass as Class<out XBreakpointType<XLineBreakpoint<XBreakpointProperties<*>>, *>>
    }
}

/** Expressions are C: in a C document where the IDE knows C, plain text where it does not. */
internal object CExpressionEditorsProvider : XDebuggerEditorsProvider() {

    override fun getFileType(): FileType =
        FileTypeManager.getInstance().getFileTypeByExtension("c").takeIf { it !is UnknownFileType } ?: PlainTextFileType.INSTANCE

    override fun createDocument(project: Project, expression: XExpression, sourcePosition: XSourcePosition?, mode: EvaluationMode): Document =
        EditorFactory.getInstance().createDocument(expression.expression)
}
