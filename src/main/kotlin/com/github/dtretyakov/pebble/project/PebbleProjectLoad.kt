package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.build.PebbleBuild
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.EditorNotifications
import java.nio.file.Files
import java.nio.file.Path

/**
 * Loading a Pebble project: the one build it needs before C has any code insight.
 *
 * What a C file is compiled with — the SDK's include directory for the platform, the platform's
 * defines, and the headers waf generates, `message_keys.auto.h` and `resource_ids.auto.h`, that
 * `pebble.h` itself includes — is known only to waf, and only once it has configured the project.
 * `pebble compile-commands` reads it from waf's cache, which a build creates. A project that had
 * never been built was therefore a page of false errors, starting with "'pebble.h' file not found".
 *
 * So the first open builds, in the background, the way an IDE loads a CMake or Gradle project
 * when it is first opened; the compile database follows the build, and clangd follows the
 * database. A build that fails on the code still leaves waf's cache behind, which is all the
 * database needs.
 */
@Service(Service.Level.PROJECT)
class PebbleProjectLoad(private val project: Project) {

    @Volatile
    var isLoading: Boolean = false
        private set

    /** Builds once when waf has never configured the project for its platform, if it can build at all. */
    fun loadIfNeeded() {
        val model = PebbleProject.getInstance(project)
        val root = model.primaryRoot() ?: return
        val platform = model.activePlatform(root) ?: return
        if (needsLoad(root, platform)) load()
    }

    /** Builds now, for code insight; the Build link above a C file without a database. */
    fun load() {
        val root = PebbleProject.getInstance(project).primaryRoot() ?: return
        if (PebbleSdkService.getInstance().tool() == null || PebbleSdkService.getInstance().currentSdk() == null) return
        if (isLoading) return
        isLoading = true
        EditorNotifications.getInstance(project).updateAllNotifications()

        object : Task.Backgroundable(project, "Loading the Pebble project", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Building once, to learn how the C is compiled"
                PebbleBuild.run(project, root, debug = false, indicator = indicator)
            }

            override fun onFinished() {
                isLoading = false
                // The build announced itself, and the compile database follows it; the banner over
                // C files has its "loading" to take back either way.
                EditorNotifications.getInstance(project).updateAllNotifications()
            }
        }.queue()
    }

    /** Runs [loadIfNeeded] when a Pebble project opens. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) = getInstance(project).loadIfNeeded()
    }

    companion object {
        fun getInstance(project: Project): PebbleProjectLoad = project.service()

        /** Whether waf has never configured [root] for [platform]: no cache, so nothing to read flags from. */
        fun needsLoad(root: Path, platform: String): Boolean =
            !Files.exists(root.resolve("build/c4che/${platform}_cache.py"))
    }
}
