package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.project.NewPebbleProject.Language
import com.github.dtretyakov.pebble.project.NewPebbleProject.Options
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

class NewPebbleProjectTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `C options become pebble-tool's flags`() {
        assertEquals(
            listOf("new-project", "--c", "--simple", "--javascript", "--worker", "face"),
            NewPebbleProject.arguments("face", Options(Language.C, javascript = true, worker = true, simple = true)),
        )
        assertEquals(listOf("new-project", "--c", "app"), NewPebbleProject.arguments("app", Options(javascript = false)))
    }

    @Test
    fun `Alloy takes none of the C flags`() {
        assertEquals(
            listOf("new-project", "--alloy", "face"),
            NewPebbleProject.arguments("face", Options(Language.ALLOY, javascript = true, worker = true, simple = true)),
        )
    }

    @Test
    fun `watchface is set in package json and nothing else changes`() {
        val template = javaClass.getResource("/fixture-c/package.json")!!.readText()
        val file = dir.resolve("package.json").also { it.writeText(template) }
        NewPebbleProject.setWatchface(file, true)
        val edited = file.readText()
        assertTrue(edited.contains("\"watchface\": true"))
        assertEquals(template.replace("\"watchface\": false", "\"watchface\": true"), edited)
        assertEquals(true, PebbleProjectInfo.parse(edited)?.watchface)
    }
}
