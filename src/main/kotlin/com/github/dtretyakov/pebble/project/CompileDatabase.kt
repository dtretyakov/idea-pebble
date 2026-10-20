package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.build.BuildResult
import com.github.dtretyakov.pebble.build.PebbleBuild
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.Topic
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.readText

/**
 * `compile_commands.json` for the active platform — what CLion reads natively and what clangd is
 * started with everywhere else.
 *
 * Written by `pebble compile-commands`, which reads the flags waf resolved for a platform, so
 * `#if defined(PBL_COLOR)` resolves as the chosen watch will compile it. Rewritten when the active
 * platform changes and after a build, when the file is missing or describes another platform; it
 * needs waf's cache for the platform, which only a configure or a build creates, so before the
 * first build there may be nothing to write yet.
 */
@Service(Service.Level.PROJECT)
class CompileDatabase(private val project: Project) {

    private val running = AtomicBoolean(false)

    /** Writes the database for the active platform unless it already describes it. */
    fun refresh(force: Boolean = false) {
        val application = ApplicationManager.getApplication()
        // It reads the disk to decide, which for a project in WSL is a request to the distribution.
        if (application.isDispatchThread) {
            application.executeOnPooledThread { if (!project.isDisposed) refresh(force) }
            return
        }
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot() ?: return
        val platform = model.activePlatform(root) ?: return
        if (!force && platformOf(root) == platform) return
        if (!Files.exists(root.resolve("build/c4che/${platform}_cache.py"))) return
        val tool = PebbleSdkService.getInstance().tool() ?: return
        if (!running.compareAndSet(false, true)) return

        object : Task.Backgroundable(project, "Updating the C compile database for $platform", false) {
            override fun run(indicator: ProgressIndicator) {
                val output = CapturingProcessHandler(
                    PebbleCommands.command(tool, listOf("compile-commands", "--platform", platform), root),
                ).runProcessWithProgressIndicator(indicator)
                if (output.exitCode != 0) {
                    LOG.info("pebble compile-commands failed: ${output.stderr.ifBlank { output.stdout }}")
                }
                LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve(FILE_NAME)))
                if (output.exitCode == 0 && !project.isDisposed) project.messageBus.syncPublisher(WRITTEN).written(project)
            }

            override fun onFinished() {
                running.set(false)
                // The platform may have changed again while this one was being written; a failure is
                // not tried again until the next build or switch.
                if (!project.isDisposed && PebbleProject.getInstance(project).activePlatform(root) != platform) refresh()
            }
        }.queue()
    }

    /** The platform a database was written for, from the `-DPBL_PLATFORM_…` every entry carries. */
    fun platformOf(root: Path): String? {
        val file = root.resolve(FILE_NAME)
        if (!Files.isRegularFile(file)) return null
        return runCatching { file.readText() }.getOrNull()?.let(::platformIn)
    }

    /** Listens for what makes the database stale: a platform switch, and a build. */
    class Listener : PebbleSettings.Listener, PebbleBuild.Listener {
        override fun activePlatformChanged(project: Project) = getInstance(project).refresh()

        override fun buildFinished(project: Project, root: Path, result: BuildResult) = getInstance(project).refresh()
    }

    /** And when the project opens, in case it was last built for another platform. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) = getInstance(project).refresh()
    }

    /** Told when a database has been written: clangd waits for the first one before it starts. */
    fun interface Written {
        fun written(project: Project)
    }

    /** Whether there is a database for clangd to read at all, for any platform. */
    fun exists(): Boolean {
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot() ?: return false
        // Asked on the UI thread, by LSP4IJ and the editor banner: from the virtual file system,
        // which [refresh] tells of every database it writes.
        return when (val found = model.findFile(root, FILE_NAME)) {
            PebbleProject.Found.Unknown -> !model.isUiThread() && Files.isRegularFile(root.resolve(FILE_NAME))
            PebbleProject.Found.Absent -> false
            is PebbleProject.Found.File -> !found.file.isDirectory
        }
    }

    companion object {
        const val FILE_NAME = "compile_commands.json"

        @Topic.ProjectLevel
        val WRITTEN: Topic<Written> = Topic.create("Pebble compile database written", Written::class.java)

        private val PLATFORM_DEFINE = Regex("""-DPBL_PLATFORM_([A-Z]+)""")
        private val LOG = logger<CompileDatabase>()

        fun getInstance(project: Project): CompileDatabase = project.service()

        internal fun platformIn(database: String): String? =
            PLATFORM_DEFINE.find(database)?.groupValues?.get(1)?.lowercase()
    }
}
