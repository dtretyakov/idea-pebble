package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.project.PebbleProject
import com.github.dtretyakov.pebble.project.PebbleProjectInfo
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType

/**
 * A line breakpoint in an Alloy app's JavaScript or TypeScript — for IDEs without the JavaScript
 * debugger.
 *
 * Where the JavaScript debugger is installed (IntelliJ IDEA Ultimate, WebStorm, CLion), its own
 * line breakpoints are the ones a click in the gutter sets, and the Alloy debugger honours them.
 * Two types offered on one line would make every click ask which to set, so this one stands
 * aside there.
 */
class AlloyLineBreakpointType : XLineBreakpointType<XBreakpointProperties<*>>(ID, "Alloy Line Breakpoints") {

    override fun createBreakpointProperties(file: VirtualFile, line: Int): XBreakpointProperties<*>? = null

    override fun canPutAt(file: VirtualFile, line: Int, project: Project): Boolean =
        offersOwnType(javascriptPresent = AlloyBreakpoints.javascriptLineType() != null, alloySource = AlloyBreakpoints.isAlloySource(project, file)) &&
            FileDocumentManager.getInstance().getDocument(file)?.let { document ->
                line in 0 until document.lineCount &&
                    document.getText(com.intellij.openapi.util.TextRange(document.getLineStartOffset(line), document.getLineEndOffset(line))).isNotBlank()
            } == true

    companion object {
        const val ID = "pebble-alloy-line"

        fun offersOwnType(javascriptPresent: Boolean, alloySource: Boolean): Boolean = !javascriptPresent && alloySource
    }
}

/** Breaks where an Alloy app throws, caught or not — the engine knows no difference. */
class AlloyExceptionBreakpointType :
    XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>(ID, "Alloy Exceptions") {

    override fun getDisplayText(breakpoint: XBreakpoint<XBreakpointProperties<*>>): String = "Any exception in an Alloy app"

    @Suppress("UNCHECKED_CAST")
    override fun createDefaultBreakpoint(creator: XBreakpointCreator<XBreakpointProperties<*>>): XBreakpoint<XBreakpointProperties<*>> =
        creator.createBreakpoint(null as XBreakpointProperties<*>?)

    override fun shouldShowInBreakpointsDialog(project: Project): Boolean = AlloyBreakpoints.isAlloyProject(project)

    companion object {
        const val ID = "pebble-alloy-exceptions"
    }
}

internal object AlloyBreakpoints {

    /** The JavaScript debugger's line breakpoint type, found by its id: no dependency on that plugin. */
    fun javascriptLineType(): XLineBreakpointType<*>? =
        XBreakpointType.EXTENSION_POINT_NAME.extensionList.firstOrNull { it.id == JAVASCRIPT } as? XLineBreakpointType<*>

    fun isAlloySource(project: Project, file: VirtualFile): Boolean {
        val model = PebbleProject.getInstance(project)
        val root = model.rootFor(file) ?: return false
        if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return false
        val path = file.toNioPathOrNull() ?: return false
        return AlloySources.isAlloySource(root, path)
    }

    fun isAlloyProject(project: Project): Boolean {
        val model = PebbleProject.getInstance(project)
        return model.roots().any { model.infoAt(it)?.projectType == PebbleProjectInfo.ProjectType.ALLOY }
    }

    private const val JAVASCRIPT = "javascript"
}

/** Line breakpoints of one type — ours or the JavaScript debugger's — given to the Alloy debugger. */
internal class AlloyLineBreakpointHandler(
    type: Class<out XBreakpointType<XLineBreakpoint<XBreakpointProperties<*>>, *>>,
    private val process: AlloyDebugProcess,
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

internal class AlloyExceptionBreakpointHandler(private val process: AlloyDebugProcess) :
    XBreakpointHandler<XBreakpoint<XBreakpointProperties<*>>>(AlloyExceptionBreakpointType::class.java) {

    override fun registerBreakpoint(breakpoint: XBreakpoint<XBreakpointProperties<*>>) = process.setExceptionBreakpoint(breakpoint)

    override fun unregisterBreakpoint(breakpoint: XBreakpoint<XBreakpointProperties<*>>, temporary: Boolean) =
        process.setExceptionBreakpoint(null)
}
