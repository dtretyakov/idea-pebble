package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.project.PebbleProject
import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.impl.FailureResultImpl
import com.intellij.build.events.impl.SuccessResultImpl
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Check Release: an ordinary build — never a debug one, which an Alloy app would ship waiting for
 * a debugger — and [ReleaseCheck]'s checks of it, in the Build tool window, grouped by what they
 * are about, a failure for anything the appstore would refuse.
 */
object PebbleRelease {

    fun check(project: Project, root: Path, indicator: ProgressIndicator?): List<ReleaseCheck.Item> {
        val build = PebbleBuild.run(project, root, debug = false, indicator = indicator)
        val targets = runReadAction { PebbleProject.getInstance(project).infoAt(root)?.targetPlatforms.orEmpty() }
        val items = buildList {
            if (!build.succeeded) add(ReleaseCheck.Item("error", "build", build.problem ?: "The release build failed: see the build above."))
            val packageJson = root.resolve("package.json")
            if (packageJson.isRegularFile()) addAll(ReleaseCheck.packageJson(packageJson.readText()))
            if (build.succeeded) {
                addAll(ReleaseCheck.bundle(build.bundle, targets))
                addAll(ReleaseCheck.memory(build.memory))
            }
        }
        report(project, root, items)
        return items
    }

    private fun report(project: Project, root: Path, items: List<ReleaseCheck.Item>) {
        val view = project.getService(BuildViewManager::class.java)
        val id = Any()
        view.onEvent(id, StartBuildEvent.builder("Pebble release check", DefaultBuildDescriptor(id, "Pebble release", root.toString(), System.currentTimeMillis())).build())
        for (item in items) {
            val kind = when (item.severity) {
                "error" -> MessageEvent.Kind.ERROR
                "warning" -> MessageEvent.Kind.WARNING
                else -> MessageEvent.Kind.INFO
            }
            view.onEvent(id, MessageEvent.builder(item.message, kind).withParentId(id).withGroup(item.check).build())
        }
        val errors = items.count { it.severity == "error" }
        val summary = if (errors == 0) "ready for the appstore" else "$errors problem(s) the appstore would refuse"
        view.onEvent(
            id,
            FinishBuildEvent.builder(id, summary, if (errors == 0) SuccessResultImpl() else FailureResultImpl())
                .withTime(System.currentTimeMillis()).build(),
        )
    }
}
