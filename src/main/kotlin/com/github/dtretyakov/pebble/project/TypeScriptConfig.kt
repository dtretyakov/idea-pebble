package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.build.BuildResult
import com.github.dtretyakov.pebble.build.PebbleBuild
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `tsconfig.json` for an Alloy app, so the IDE checks `src/embeddedjs` the way the build does.
 *
 * The SDK's build writes the TypeScript settings it checks the app with — module paths onto the
 * SDK's typings, the global types, the language level — to
 * `build/mods/<platform>/mcrun/tmp/pebble/<debug|release>/embeddedjs/files/tsconfig-js.json`, for
 * itself. Without a `tsconfig.json` the IDE knows none of it, and `import Poco from
 * "commodetto/Poco"` resolves to nothing. This one extends the build's, for the active platform,
 * and is rewritten after each build; one the user wrote, without this file's marker, is left
 * alone.
 */
object TypeScriptConfig {

    const val FILE_NAME = "tsconfig.json"
    private const val MARKER = "//"
    private const val NOTE = "Written by the Pebble plugin after each build, from the build's own TypeScript settings. Remove this line to keep the file as yours."

    /** The settings the last build for [platform] wrote, the most recent of its debug and release ones. */
    fun generated(root: Path, platform: String): Path? {
        val pebble = root.resolve("build/mods").resolve(platform).resolve("mcrun/tmp/pebble")
        if (!pebble.isDirectory()) return null
        return Files.list(pebble).use { variants ->
            variants.map { it.resolve("embeddedjs/files/tsconfig-js.json") }.filter { it.isRegularFile() }.toList()
        }.maxByOrNull { Files.getLastModifiedTime(it) }
    }

    fun text(root: Path, generated: Path): String =
        """
        |{
        |  "$MARKER": "$NOTE",
        |  "extends": "./${root.relativize(generated).invariantSeparatorsPathString}",
        |  "files": [],
        |  "include": ["src/embeddedjs/**/*"]
        |}
        |""".trimMargin()

    /** Whether [file] is one this plugin wrote, or absent: either may be (re)written. */
    fun isOurs(file: Path): Boolean {
        if (!file.isRegularFile()) return true
        val json = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return false
        return json[MARKER]?.jsonPrimitive?.contentOrNull?.startsWith("Written by the Pebble plugin") == true
    }

    /** Writes [root]'s `tsconfig.json` for [platform]; false when there is nothing to extend, or the file is the user's. */
    fun update(root: Path, platform: String): Boolean {
        val file = root.resolve(FILE_NAME)
        val generated = generated(root, platform) ?: return false
        if (!isOurs(file)) return false
        val text = text(root, generated)
        if (file.isRegularFile() && file.readText() == text) return true
        file.writeText(text)
        LocalFileSystem.getInstance().refreshNioFiles(listOf(file))
        return true
    }

    private suspend fun refresh(project: Project) {
        val (root, alloy, platform) = readAction {
            val model = PebbleProject.getInstance(project)
            val root = model.primaryRoot()
            Triple(root, root?.let { model.infoAt(it)?.projectType == PebbleProjectInfo.ProjectType.ALLOY } == true, root?.let(model::activePlatform))
        }
        if (root != null && alloy && platform != null) update(root, platform)
    }

    /** After each build, and on a switch of platform. */
    class Listener : PebbleBuild.Listener, PebbleSettings.Listener {
        override fun buildFinished(project: Project, root: Path, result: BuildResult) {
            val model = PebbleProject.getInstance(project)
            if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return
            model.activePlatform(root)?.let { update(root, it) }
        }

        override fun activePlatformChanged(project: Project) {
            val model = PebbleProject.getInstance(project)
            val root = model.primaryRoot() ?: return
            if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return
            model.activePlatform(root)?.let { update(root, it) }
        }
    }

    /** And when the project opens, built before. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) = refresh(project)
    }
}
