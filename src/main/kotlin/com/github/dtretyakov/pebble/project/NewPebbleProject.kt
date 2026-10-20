package com.github.dtretyakov.pebble.project

import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFileVisitor
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A new project, made by `pebble new-project` from the SDK's own templates — so a project made in
 * the IDE is the project the documentation, the CLI and everyone else's tools expect.
 */
object NewPebbleProject {

    enum class Language(val display: String) {
        C("C"),
        ALLOY("Alloy (JavaScript)"),
    }

    data class Options(
        var language: Language = Language.C,
        var watchface: Boolean = false,
        /** PebbleKit JS, the half of the app that runs on the phone. C only: Alloy always has it. */
        var javascript: Boolean = true,
        var worker: Boolean = false,
        /** A bare `main` rather than the template's windows and handlers. */
        var simple: Boolean = false,
        /** The `pebble` Agent Skill in the project's skill directories, for coding agents. */
        var agentSkill: Boolean = true,
    )

    fun arguments(name: String, options: Options): List<String> = buildList {
        add("new-project")
        when (options.language) {
            Language.ALLOY -> add("--alloy")
            Language.C -> {
                add("--c")
                if (options.simple) add("--simple")
                if (options.javascript) add("--javascript")
                if (options.worker) add("--worker")
            }
        }
        add(name)
    }

    /**
     * [generate], from the New Project wizards, which ask on the UI thread: under a modal progress,
     * so the process runs off it — in WSL it starts the distribution first, which takes seconds.
     */
    fun generateWithProgress(project: Project?, tool: Path, target: Path, options: Options) {
        val work: () -> Unit = {
            generate(tool, target, options)
            // Seen by the IDE before the wizard goes on, there on the UI thread, which reads only
            // what the IDE has seen.
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.let { VfsUtilCore.visitChildrenRecursively(it, object : VirtualFileVisitor<Unit>() {}) }
            Unit
        }
        val application = ApplicationManager.getApplication()
        // Under a write action, as some IDEs call a project generator, no progress can be shown.
        if (!application.isDispatchThread || application.isWriteAccessAllowed) return work()
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            ThrowableComputable<Unit, IOException> { work() },
            "Creating the Pebble project",
            false,
            project,
        )
    }

    /**
     * Creates the project in [target], which may already exist — the IDE makes the directory
     * before it asks for the contents. pebble-tool refuses a directory that exists, so the project
     * is made beside it and moved in: beside it rather than in the system's temporary directory,
     * which under WSL is a Windows directory on another disk, and from which a directory cannot be
     * moved into the distribution's.
     */
    @OptIn(ExperimentalPathApi::class)
    fun generate(tool: Path, target: Path, options: Options) {
        val name = target.name
        val scratch = target.parent?.takeIf { Files.isDirectory(it) }
            ?.let { runCatching { Files.createTempDirectory(it, ".pebble-new-") }.getOrNull() }
            ?: Files.createTempDirectory("pebble-new-project")
        try {
            val output = CapturingProcessHandler(PebbleCommands.command(tool, arguments(name, options), scratch))
                .runProcess(TIMEOUT_MILLIS)
            val made = scratch.resolve(name)
            if (output.exitCode != 0 || !made.exists()) {
                throw IOException(
                    "pebble new-project failed: " + output.stderr.ifBlank { output.stdout }.trim().lines().lastOrNull().orEmpty(),
                )
            }
            Files.createDirectories(target)
            made.listDirectoryEntries().forEach { entry ->
                val destination = target.resolve(entry.name)
                if (!destination.exists()) Files.move(entry, destination)
            }
            setWatchface(target.resolve(PebbleProjectInfo.FILE_NAME), options.watchface)
            if (options.agentSkill) com.github.dtretyakov.pebble.agent.AgentSkill.write(target)
        } finally {
            scratch.deleteRecursively()
        }
    }

    /**
     * Sets `watchapp.watchface`, which the templates fix — false for C, true for Alloy — and
     * pebble-tool offers no flag for. Edited as text, so the file keeps the template's layout.
     */
    internal fun setWatchface(packageJson: Path, watchface: Boolean) {
        if (!packageJson.isRegularFile()) return
        val text = packageJson.readText()
        val edited = WATCHFACE.replace(text) { "${it.groupValues[1]}$watchface" }
        if (edited != text) packageJson.writeText(edited)
    }

    private val WATCHFACE = Regex("""("watchface"\s*:\s*)(?:true|false)""")

    private const val TIMEOUT_MILLIS = 120_000
}
