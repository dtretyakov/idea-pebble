package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PebbleToolTest {

    @TempDir
    lateinit var home: Path

    @BeforeEach
    fun posixOnly() {
        // pebble-tool does not run on Windows outside WSL, and an executable bit is what is checked.
        assumeTrue(HostOs.current() != HostOs.WINDOWS)
    }

    @Test
    fun `the PATH comes first`() {
        val onPath = executable(home.resolve("bin"))
        executable(home.resolve(".local/bin"))
        assertEquals(onPath, PebbleTool.locate("", home.resolve("bin").toString(), home) { null })
    }

    @Test
    fun `uv's bin directory is found when the IDE's PATH lacks it`() {
        val uv = executable(home.resolve(".local/bin"))
        assertEquals(uv, PebbleTool.locate("", "/usr/bin${File.pathSeparator}/bin", home) { null })
    }

    @Test
    fun `UV_TOOL_BIN_DIR is honoured`() {
        val custom = executable(home.resolve("tools"))
        assertEquals(
            custom,
            PebbleTool.locate("", null, home) { if (it == "UV_TOOL_BIN_DIR") home.resolve("tools").toString() else null },
        )
    }

    @Test
    fun `a configured path is used, and not replaced when it is wrong`() {
        executable(home.resolve(".local/bin"))
        val configured = executable(home.resolve("custom"))
        assertEquals(configured, PebbleTool.locate(configured.toString(), null, home) { null })
        assertNull(PebbleTool.locate(home.resolve("nowhere/pebble").toString(), null, home) { null })
    }

    @Test
    fun `a file that is not executable is not the tool`() {
        home.resolve(".local/bin").createDirectories().resolve("pebble").writeText("")
        assertNull(PebbleTool.locate("", null, home) { null })
    }

    @Test
    fun `the version is read from --version`() {
        assertEquals("5.0.40", PebbleTool.parseVersion("Pebble Tool v5.0.40 (active SDK: v4.33.1)\n"))
        assertEquals("5.0.40", PebbleTool.parseVersion("Pebble Tool v5.0.40"))
        assertNull(PebbleTool.parseVersion("command not found"))
    }

    private fun executable(dir: Path): Path {
        val file = dir.createDirectories().resolve("pebble")
        file.writeText("#!/bin/sh\n")
        file.toFile().setExecutable(true)
        return file
    }
}
