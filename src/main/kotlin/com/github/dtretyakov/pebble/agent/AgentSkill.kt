package com.github.dtretyakov.pebble.agent

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * The `pebble` Agent Skill, which teaches a coding agent the Pebble tools and the platforms'
 * ways: the files of `agents/plugin/skills/pebble` in this repository, carried in the plugin to
 * be written into a project's `.claude/skills/pebble`, where Claude Code — and agents that read
 * the same format there — find it.
 */
object AgentSkill {

    const val DIRECTORY = ".claude/skills/pebble"

    val FILES = listOf("SKILL.md", "references/platforms.md", "references/without-ide.md")

    fun text(file: String): String =
        AgentSkill::class.java.getResource("/agent-skills/pebble/$file")?.readText()
            ?: error("The plugin is missing its agent skill's $file")

    /** The skill's files already in [root] that the plugin's version would change. */
    fun changes(root: Path): List<String> = FILES.filter { file ->
        val existing = root.resolve(DIRECTORY).resolve(file)
        existing.isRegularFile() && existing.readText() != text(file)
    }

    /** Writes the skill into [root]; returns its `SKILL.md`. */
    fun write(root: Path): Path {
        val directory = root.resolve(DIRECTORY)
        for (file in FILES) {
            val target = directory.resolve(file)
            target.parent.createDirectories()
            target.writeText(text(file))
        }
        return directory.resolve(FILES.first())
    }

    fun isInstalled(root: Path): Boolean = Files.exists(root.resolve(DIRECTORY).resolve(FILES.first()))
}
