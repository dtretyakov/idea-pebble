package com.github.dtretyakov.pebble.run

import com.github.dtretyakov.pebble.build.HeapUsage
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.util.Key

/**
 * Says so when an app exits with memory it never freed.
 *
 * The firmware logs every app's heap as it exits — `Heap Usage for App <…>: … Still allocated <24B>`
 * — in the same log the run shows. The line is there either way; this makes a leak read as one.
 */
object HeapLeakHint {

    fun text(usage: HeapUsage): String =
        "${usage.app} exited with ${usage.stillAllocated} bytes still allocated: memory it never freed.\n"

    fun attach(handler: ProcessHandler) {
        handler.addProcessListener(
            object : ProcessListener {
                private val pending = StringBuilder()

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType == ProcessOutputTypes.SYSTEM) return
                    val lines = synchronized(pending) {
                        pending.append(event.text)
                        val end = pending.lastIndexOf("\n")
                        if (end < 0) return
                        pending.substring(0, end).also { pending.delete(0, end + 1) }.lines()
                    }
                    for (line in lines) {
                        val usage = HeapUsage.parse(line) ?: continue
                        if (usage.stillAllocated > 0) handler.notifyTextAvailable(text(usage), ProcessOutputTypes.SYSTEM)
                    }
                }
            },
        )
    }
}
