package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.debug.XsbugCommands.Breakpoint
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider

/**
 * What the engine is told for a line breakpoint, from what the IDE's breakpoint says.
 *
 * - A condition goes to the engine, compiled: it decides there whether to stop.
 * - A breakpoint that does not suspend but logs — "Breakpoint hit" or an expression — becomes
 *   the engine's trace, which logs and goes on without stopping at all.
 * - One that neither suspends nor logs is not sent.
 * - One that suspends and logs an expression stops; the expression is evaluated then.
 */
internal object AlloyBreakpointTargets {

    sealed interface Made {
        data class Target(val breakpoint: Breakpoint) : Made
        data class Invalid(val message: String) : Made
        data object Nothing : Made
    }

    fun of(breakpoint: XLineBreakpoint<*>, enginePath: String, fileName: String, compiler: ExpressionCompiler?): Made = of(
        enginePath,
        breakpoint.line + 1,
        fileName,
        condition = breakpoint.conditionExpression?.expression,
        logExpression = breakpoint.logExpressionObject?.expression,
        logMessage = breakpoint.isLogMessage,
        suspends = breakpoint.suspendPolicy != SuspendPolicy.NONE,
        compile = { compiler?.compile(it) },
    )

    fun of(
        path: String,
        line: Int,
        fileName: String,
        condition: String?,
        logExpression: String?,
        logMessage: Boolean,
        suspends: Boolean,
        compile: (String) -> XscCompiler.Result?,
    ): Made {
        val conditionText = condition?.trim()?.ifEmpty { null }
        val logText = logExpression?.trim()?.ifEmpty { null }
        val trace = if (suspends) {
            null
        } else {
            listOfNotNull(
                "\"Breakpoint reached at $fileName:$line\"".takeIf { logMessage },
                logText?.let { "($it)" },
            ).ifEmpty { return Made.Nothing }.joinToString(" + \" \" + ")
        }
        fun compiled(expression: String): String? = when (val result = compile(expression)) {
            is XscCompiler.Result.Compiled -> result.hex
            is XscCompiler.Result.Failed -> throw Problem("Cannot compile $expression: ${result.message}")
            null -> throw Problem("This SDK has no xsc to compile $expression with")
        }
        return try {
            Made.Target(Breakpoint(path, line, condition = conditionText?.let(::compiled), trace = trace?.let(::compiled)))
        } catch (e: Problem) {
            Made.Invalid(e.message!!)
        }
    }

    private class Problem(message: String) : Exception(message)
}

/** Expressions are JavaScript; a plain document where the IDE has no JavaScript support. */
internal object AlloyEditorsProvider : XDebuggerEditorsProvider() {

    override fun getFileType(): FileType = FileTypeManager.getInstance().getFileTypeByExtension("js")

    override fun createDocument(project: Project, expression: XExpression, sourcePosition: XSourcePosition?, mode: EvaluationMode): Document =
        EditorFactory.getInstance().createDocument(expression.expression)
}
