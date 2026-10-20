package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.build.BuildResult
import com.github.dtretyakov.pebble.build.PebbleBuild
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.ToolHost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private const val NOTE = "Written by Develop for Pebble after each build, from the build's own TypeScript settings. Remove this line to keep the file as yours."

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

    /**
     * The same for a build that ran in WSL, whose settings name the SDK's typings by Linux path —
     * `/home/me/.local/share/pebble-sdk/…` — which the IDE's TypeScript, on Windows, would look for
     * on a drive. Extending them would resolve nothing, so they are copied in, each absolute path
     * spelled as the IDE reaches it ([idePath]) and each relative one made absolute from where the
     * build wrote it. Null when the build's file cannot be read.
     */
    fun text(root: Path, generated: Path, idePath: (String) -> Path?): String? {
        val settings = runCatching { Json.parseToJsonElement(generated.readText()) as? JsonObject }.getOrNull() ?: return null
        val options = settings["compilerOptions"] as? JsonObject ?: JsonObject(emptyMap())
        val base = generated.parent

        fun spell(text: String): String = when {
            text.startsWith("/") -> idePath(text)?.invariantSeparatorsPathString ?: text
            text.startsWith("./") || text.startsWith("../") -> base.resolve(text).normalize().invariantSeparatorsPathString
            else -> text
        }

        fun rewrite(element: JsonElement): JsonElement = when (element) {
            is JsonPrimitive -> if (element.isString) JsonPrimitive(spell(element.content)) else element
            is JsonArray -> JsonArray(element.map(::rewrite))
            is JsonObject -> JsonObject(element.mapValues { (_, value) -> rewrite(value) })
        }

        val written = JsonObject(
            linkedMapOf(
                MARKER to JsonPrimitive(NOTE),
                "compilerOptions" to rewrite(options),
                "files" to JsonArray(emptyList()),
                "include" to JsonArray(listOf(JsonPrimitive("src/embeddedjs/**/*"))),
            ),
        )
        return PRETTY.encodeToString(JsonObject.serializer(), written) + "\n"
    }

    private val PRETTY = Json { prettyPrint = true }

    /** Whether [file] is one this plugin wrote, or absent: either may be (re)written. */
    fun isOurs(file: Path): Boolean {
        if (!file.isRegularFile()) return true
        val json = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return false
        val note = json[MARKER]?.jsonPrimitive?.contentOrNull ?: return false
        // The second is how the note read before the plugin had its name.
        return note.startsWith("Written by Develop for Pebble") || note.startsWith("Written by the Pebble plugin")
    }

    /**
     * Writes [root]'s `tsconfig.json` for [platform]; false when there is nothing to extend, or the
     * file is the user's. The disk only: the IDE is told by [write], which its listeners call.
     */
    fun update(root: Path, platform: String, host: ToolHost? = null): Boolean {
        val file = root.resolve(FILE_NAME)
        val generated = generated(root, platform) ?: return false
        if (!isOurs(file)) return false
        val text = if (host == null || host is LocalToolHost) text(root, generated) else text(root, generated, host::idePath) ?: return false
        if (file.isRegularFile() && file.readText() == text) return true
        file.writeText(text)
        return true
    }

    /** [update], and the IDE told of the file, so its JavaScript reads the new settings. */
    private fun write(root: Path, platform: String) {
        if (update(root, platform, ToolHosts.currentOrLocal())) LocalFileSystem.getInstance().refreshNioFiles(listOf(root.resolve(FILE_NAME)))
    }

    private suspend fun refresh(project: Project) {
        val (root, alloy, platform) = readAction {
            val model = PebbleProject.getInstance(project)
            val root = model.primaryRoot()
            Triple(root, root?.let { model.infoAt(it)?.projectType == PebbleProjectInfo.ProjectType.ALLOY } == true, root?.let(model::activePlatform))
        }
        if (root != null && alloy && platform != null) write(root, platform)
    }

    /** After each build, and on a switch of platform. */
    class Listener : PebbleBuild.Listener, PebbleSettings.Listener {
        override fun buildFinished(project: Project, root: Path, result: BuildResult) {
            val model = PebbleProject.getInstance(project)
            if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return
            model.activePlatform(root)?.let { write(root, it) }
        }

        override fun activePlatformChanged(project: Project) {
            val model = PebbleProject.getInstance(project)
            val root = model.primaryRoot() ?: return
            if (model.infoAt(root)?.projectType != PebbleProjectInfo.ProjectType.ALLOY) return
            model.activePlatform(root)?.let { write(root, it) }
        }
    }

    /** And when the project opens, built before. */
    class Startup : ProjectActivity {
        override suspend fun execute(project: Project) = refresh(project)
    }
}
