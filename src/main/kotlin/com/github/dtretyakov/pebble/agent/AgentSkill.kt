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
 * be written into a project where agents look for skills:
 *
 * - `.agents/skills`, the agents' shared directory: Codex, Junie, Cursor and Copilot read it;
 * - `.claude/skills`, Claude Code's own, which Cursor and Copilot read too.
 */
object AgentSkill {

    val DIRECTORIES = listOf(".agents/skills/pebble", ".claude/skills/pebble")

    /** Named where one is meant: the directories, as one line. */
    val WHERE: String get() = DIRECTORIES.joinToString(" and ")

    val FILES = listOf("SKILL.md", "references/platforms.md", "references/without-ide.md")

    fun text(file: String): String =
        AgentSkill::class.java.getResource("/agent-skills/pebble/$file")?.readText()
            ?: error("The plugin is missing its agent skill's $file")

    /** The skill's files already in [root] that the plugin's version would change, by their path in the project. */
    fun changes(root: Path): List<String> = DIRECTORIES.flatMap { directory ->
        FILES.filter { file ->
            val existing = root.resolve(directory).resolve(file)
            existing.isRegularFile() && existing.readText() != text(file)
        }.map { "$directory/$it" }
    }

    /** Writes the skill into each of [root]'s skill directories; returns the first one's `SKILL.md`. */
    fun write(root: Path): Path {
        for (directory in DIRECTORIES) {
            for (file in FILES) {
                val target = root.resolve(directory).resolve(file)
                target.parent.createDirectories()
                target.writeText(text(file))
            }
        }
        return root.resolve(DIRECTORIES.first()).resolve(FILES.first())
    }

    fun isInstalled(root: Path): Boolean = DIRECTORIES.all { Files.exists(root.resolve(it).resolve(FILES.first())) }
}
