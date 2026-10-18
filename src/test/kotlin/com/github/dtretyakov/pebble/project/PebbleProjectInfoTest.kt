package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.project.PebbleProjectInfo.ProjectType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PebbleProjectInfoTest {

    @Test
    fun `the C template is a native watchapp for every platform`() {
        val info = fixture("fixture-c")
        assertEquals("fixture-c", info.name)
        assertEquals("Fixture C", info.displayName)
        assertEquals("1.0.0", info.version)
        assertEquals(ProjectType.NATIVE, info.projectType)
        assertEquals(listOf("aplite", "basalt", "chalk", "diorite", "emery", "flint", "gabbro"), info.targetPlatforms)
        assertFalse(info.watchface)
    }

    @Test
    fun `the Alloy template is a watchface for emery and gabbro`() {
        val info = fixture("fixture-alloy")
        assertEquals(ProjectType.ALLOY, info.projectType)
        assertEquals(listOf("emery", "gabbro"), info.targetPlatforms)
        assertTrue(info.watchface)
    }

    @Test
    fun `a package json without a pebble key is not a Pebble project`() {
        assertNull(PebbleProjectInfo.parse("""{"name": "web-app", "dependencies": {}}"""))
    }

    @Test
    fun `broken JSON is not a Pebble project, and does not throw`() {
        assertNull(PebbleProjectInfo.parse("""{"pebble": {"""))
        assertNull(PebbleProjectInfo.parse("[]"))
    }

    @Test
    fun `missing fields are read the way pebble-tool reads them`() {
        val info = PebbleProjectInfo.parse("""{"pebble": {}}""")
        assertNotNull(info)
        assertEquals(ProjectType.NATIVE, info!!.projectType)
        assertEquals(emptyList<String>(), info.targetPlatforms)
        assertFalse(info.watchface)
        assertNull(info.version)
    }

    @Test
    fun `an unknown project type is kept as unknown rather than guessed`() {
        assertEquals(ProjectType.UNKNOWN, PebbleProjectInfo.parse("""{"pebble": {"projectType": "pebblejs"}}""")?.projectType)
    }

    private fun fixture(name: String): PebbleProjectInfo {
        val text = javaClass.getResource("/$name/package.json")!!.readText()
        return PebbleProjectInfo.parse(text)!!
    }
}
