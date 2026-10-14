package com.github.dtretyakov.pebble.run

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.AnsiEscapeDecoder
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key

/**
 * pebble-tool's output with its colours, less the log lines below [minimum].
 *
 * The level is in the escape sequences, so lines are judged before the colours are turned into
 * console attributes — which is what `ColoredProcessHandler` does, and why this is its own
 * handler: that one's text hook is final. The decoding is the platform's own [AnsiEscapeDecoder].
 */
class PebbleLogProcessHandler(commandLine: GeneralCommandLine, private val minimum: LogLevel) :
    KillableProcessHandler(commandLine), AnsiEscapeDecoder.ColoredTextAcceptor {

    private val decoder = AnsiEscapeDecoder()
    private val pending = StringBuilder()

    override fun notifyTextAvailable(text: String, outputType: Key<*>) {
        if (outputType != ProcessOutputTypes.STDOUT || minimum == LogLevel.VERBOSE) {
            decoder.escapeText(text, outputType, this)
            return
        }
        synchronized(pending) {
            pending.append(text)
            val end = pending.lastIndexOf("\n")
            if (end < 0) return
            val complete = pending.substring(0, end + 1)
            pending.delete(0, end + 1)
            complete.split('\n').dropLast(1).forEach { line ->
                if (LogLevel.passes(line, minimum)) decoder.escapeText(line + "\n", outputType, this)
            }
        }
    }

    override fun coloredTextAvailable(text: String, attributes: Key<*>) = super.notifyTextAvailable(text, attributes)

    override fun notifyProcessTerminated(exitCode: Int) {
        val rest = synchronized(pending) { pending.toString().also { pending.setLength(0) } }
        if (rest.isNotEmpty() && LogLevel.passes(rest, minimum)) decoder.escapeText(rest, ProcessOutputTypes.STDOUT, this)
        super.notifyProcessTerminated(exitCode)
    }
}
