package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.run.PebbleLogProcessHandler
import com.github.dtretyakov.pebble.run.PebbleRunConfiguration
import com.intellij.execution.ExecutionListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

/** The output of every Pebble run in the project — the user's and the agents' — for `pebble_logs`. */
@Service(Service.Level.PROJECT)
class PebbleRunLogs {

    val buffer = LogBuffer()

    fun attach(handler: PebbleLogProcessHandler) {
        handler.rawOutput += { text, type ->
            buffer.append(
                text,
                when (type) {
                    ProcessOutputTypes.STDERR -> LogBuffer.Source.ERROR
                    ProcessOutputTypes.SYSTEM -> LogBuffer.Source.IDE
                    else -> LogBuffer.Source.APP
                },
            )
        }
        handler.addProcessListener(
            object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) = buffer.flush()
            },
        )
    }

    /** Hears every run start, and keeps the Pebble ones. */
    class Listener(private val project: Project) : ExecutionListener {
        override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
            if (env.runProfile !is PebbleRunConfiguration || handler !is PebbleLogProcessHandler) return
            getInstance(project).attach(handler)
        }
    }

    companion object {
        fun getInstance(project: Project): PebbleRunLogs = project.service()
    }
}
