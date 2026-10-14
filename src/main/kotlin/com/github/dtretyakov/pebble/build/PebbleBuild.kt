package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.FilePosition
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.OutputBuildEvent
import com.intellij.build.events.ProgressBuildEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries

data class BuildResult(val exitCode: Int, val messages: List<BuildMessage>, val bundle: Path?) {
    val succeeded: Boolean get() = exitCode == 0
}

/**
 * `pebble build`, reported in the Build tool window.
 *
 * That is where a compile belongs in this IDE: the diagnostics become a tree the user can click
 * through, grouped by the platform that reported them, and the Run window is left to the app's
 * own logs.
 */
object PebbleBuild {

    fun interface Listener {
        /** A build finished, successfully or not; `build/` may have changed either way. */
        fun buildFinished(project: Project, root: Path, result: BuildResult)
    }

    @Topic.ProjectLevel
    val TOPIC: Topic<Listener> = Topic.create("Pebble build", Listener::class.java)

    /**
     * Builds the project at [root], blocking until it is done. Called from a background task;
     * [indicator] cancels the build when the user stops it.
     */
    fun run(project: Project, root: Path, debug: Boolean, indicator: ProgressIndicator?): BuildResult {
        val view = project.getService(BuildViewManager::class.java)
        val id = Any()
        val title = if (debug) "Pebble debug build" else "Pebble build"
        view.onEvent(
            id,
            StartBuildEvent.builder(title, DefaultBuildDescriptor(id, "Pebble", root.toString(), System.currentTimeMillis()))
                .build(),
        )

        val tool = PebbleSdkService.getInstance().tool()
        if (tool == null) {
            val reason = "pebble-tool is not installed. See Settings | Languages & Frameworks | Pebble."
            view.onEvent(id, output(id, "$reason\n", isError = true))
            view.onEvent(id, finish(id, "failed", succeeded = false))
            return BuildResult(-1, emptyList(), null)
        }

        val command = PebbleCommands.command(tool, listOfNotNull("build", "--debug".takeIf { debug }), root)
        view.onEvent(id, output(id, command.commandLineString + "\n\n"))

        val parser = BuildOutputParser(root)
        val memoryParser = MemoryReportParser()
        val messages = mutableListOf<BuildMessage>()
        val memory = mutableListOf<MemoryUsage>()
        val handler = OSProcessHandler(command)
        handler.addProcessListener(
            object : ProcessListener {
                private val pending = mapOf(
                    ProcessOutputTypes.STDOUT to StringBuilder(),
                    ProcessOutputTypes.STDERR to StringBuilder(),
                )

                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    if (outputType == ProcessOutputTypes.SYSTEM) return
                    val isError = outputType == ProcessOutputTypes.STDERR
                    view.onEvent(id, output(id, event.text, isError))
                    eachLine(pending[if (isError) ProcessOutputTypes.STDERR else ProcessOutputTypes.STDOUT]!!, event.text) {
                        read(it)
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    pending.values.forEach { read(it.toString()) }
                }

                private fun read(line: String) {
                    val before = parser.progress
                    parser.parseLine(line)?.let { messages += it }
                    memoryParser.parseLine(line)?.let { memory += it }
                    val now = parser.progress
                    if (now != null && now != before) {
                        view.onEvent(
                            id,
                            ProgressBuildEvent.builder(Any(), "Building")
                                .withParentId(id)
                                .withTime(System.currentTimeMillis())
                                .withTotal(now.second.toLong())
                                .withProgress(now.first.toLong())
                                .withUnit("steps")
                                .build(),
                        )
                    }
                }

                /** Output arrives in chunks, and a diagnostic is only readable whole. */
                private fun eachLine(buffer: StringBuilder, text: String, action: (String) -> Unit) {
                    buffer.append(text)
                    var newline = buffer.indexOf("\n")
                    while (newline >= 0) {
                        action(buffer.substring(0, newline).trimEnd('\r'))
                        buffer.delete(0, newline + 1)
                        newline = buffer.indexOf("\n")
                    }
                }
            },
        )
        handler.startNotify()
        while (!handler.waitFor(CANCEL_POLL_MILLIS)) {
            if (indicator?.isCanceled == true) handler.destroyProcess()
        }
        val exitCode = handler.exitCode ?: -1

        messages.forEach { view.onEvent(id, event(id, it)) }
        memory.forEach { view.onEvent(id, event(id, it)) }
        val bundle = bundleIn(root)
        val result = BuildResult(exitCode, messages, bundle.takeIf { exitCode == 0 })
        view.onEvent(id, finish(id, if (result.succeeded) "successful" else "failed", result.succeeded))

        // The build wrote to build/, which the IDE should see now rather than at the next focus.
        LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve("build")), true, true, null)
        project.messageBus.syncPublisher(TOPIC).buildFinished(project, root, result)
        return result
    }

    /** The newest `.pbw` in `build/`: its name is the project directory's, possibly with `_debug`. */
    private fun bundleIn(root: Path): Path? {
        val build = root.resolve("build")
        if (!Files.isDirectory(build)) return null
        return build.listDirectoryEntries().filter { it.extension == "pbw" }
            .maxByOrNull { Files.getLastModifiedTime(it) }
    }

    private fun event(id: Any, message: BuildMessage): MessageEvent {
        val kind = when (message.severity) {
            BuildMessage.Severity.ERROR -> MessageEvent.Kind.ERROR
            BuildMessage.Severity.WARNING -> MessageEvent.Kind.WARNING
        }
        val group = message.platform ?: "Build"
        val file = message.file
            ?: return MessageEvent.builder(message.text, kind).withParentId(id).withGroup(group).build()
        // Zero-based in the Build window, one-based from gcc. `toFile()` and `FileMessageEvent`
        // because their replacements are newer than the oldest IDE this plugin supports.
        @Suppress("DEPRECATION")
        val position = FilePosition(file.toFile(), (message.line ?: 1) - 1, (message.column ?: 1) - 1)
        @Suppress("DEPRECATION")
        return FileMessageEvent.builder(message.text, kind, position).withParentId(id).withGroup(group).build()
    }

    /** Each platform's memory, under that platform: a warning when the heap is short or the store would refuse the resources. */
    private fun event(id: Any, usage: MemoryUsage): MessageEvent =
        MessageEvent.builder(usage.summary, if (usage.isTight) MessageEvent.Kind.WARNING else MessageEvent.Kind.INFO)
            .withParentId(id)
            .withGroup(usage.platform)
            .build()

    private fun output(id: Any, text: String, isError: Boolean = false): BuildEvent =
        OutputBuildEvent.builder(text)
            .withParentId(id)
            .withOutputType(if (isError) ProcessOutputType.STDERR else ProcessOutputType.STDOUT)
            .build()

    private fun finish(id: Any, message: String, succeeded: Boolean): BuildEvent =
        FinishBuildEvent.builder(id, message, if (succeeded) SuccessResultImpl() else FailureResultImpl())
            .withTime(System.currentTimeMillis())
            .build()

    private const val CANCEL_POLL_MILLIS = 100L
}
