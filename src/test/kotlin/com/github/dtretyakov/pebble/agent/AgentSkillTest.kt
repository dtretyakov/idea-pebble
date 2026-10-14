package com.github.dtretyakov.pebble.agent

import com.github.dtretyakov.pebble.agent.mcp.PebbleToolset
import com.intellij.mcpserver.annotations.McpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** The skill as agents get it: from the repository's marketplace, or written by the plugin. */
class AgentSkillTest {

    private val source = Path.of("agents/plugin/skills/pebble")

    @Test
    fun `the plugin carries every file of the skill, and nothing else`() {
        val onDisk = Files.walk(source).use { paths -> paths.filter { it.isRegularFile() }.map { source.relativize(it).invariantSeparatorsPathString }.sorted().toList() }
        assertEquals(onDisk, AgentSkill.FILES.sorted())
        for (file in AgentSkill.FILES) assertEquals(source.resolve(file).readText(), AgentSkill.text(file), file)
    }

    @Test
    fun `SKILL md follows the Agent Skills format`() {
        val text = AgentSkill.text("SKILL.md")
        assertTrue(text.startsWith("---\n"))
        val frontmatter = text.removePrefix("---\n").substringBefore("\n---\n").lines().associate { it.substringBefore(":").trim() to it.substringAfter(":").trim() }
        assertEquals("pebble", frontmatter["name"], "the name is the directory's")
        assertTrue(frontmatter.getValue("name").matches(Regex("[a-z0-9]+(-[a-z0-9]+)*")))
        assertTrue(frontmatter.getValue("description").length in 1..1024)
        assertTrue(text.lines().size < 500)
        Regex("""\]\((references/[^)]+)\)""").findAll(text).forEach { assertTrue(it.groupValues[1] in AgentSkill.FILES, it.value) }
    }

    @Test
    fun `the skill names every tool there is, and no tool that is not`() {
        val tools = PebbleToolset::class.java.methods.mapNotNull { it.getAnnotation(McpTool::class.java)?.name }.toSet()
        assertEquals(9, tools.size)
        val text = AgentSkill.text("SKILL.md")
        val named = Regex("""`(pebble_[a-z_]+)`""").findAll(text).map { it.groupValues[1] }.toSet()
        assertEquals(tools, named)
    }

    @Test
    fun `the marketplace installs this plugin, versioned with the IDE plugin`() {
        val marketplace = Json.parseToJsonElement(Path.of(".claude-plugin/marketplace.json").readText()).jsonObject
        val entry = marketplace["plugins"]!!.jsonArray.single().jsonObject
        assertEquals("pebble", entry["name"]!!.jsonPrimitive.content)
        val plugin = Path.of(entry["source"]!!.jsonPrimitive.content)
        val manifest = Json.parseToJsonElement(plugin.resolve(".claude-plugin/plugin.json").readText()).jsonObject
        assertEquals("pebble", manifest["name"]!!.jsonPrimitive.content)
        val version = Properties().apply { Files.newBufferedReader(Path.of("gradle.properties")).use(::load) }.getProperty("pluginVersion")
        assertEquals(version, manifest["version"]!!.jsonPrimitive.content)
        assertEquals(source.toAbsolutePath().normalize(), plugin.resolve("skills/pebble").toAbsolutePath().normalize())
    }

    @Test
    fun `written into a project, and what a user changed there is noticed`(@TempDir root: Path) {
        val skill = AgentSkill.write(root)
        assertEquals(root.resolve(".claude/skills/pebble/SKILL.md"), skill)
        assertTrue(AgentSkill.isInstalled(root))
        assertTrue(AgentSkill.changes(root).isEmpty())
        root.resolve(".claude/skills/pebble/references/platforms.md").writeText("mine")
        assertEquals(listOf("references/platforms.md"), AgentSkill.changes(root))
    }
}
