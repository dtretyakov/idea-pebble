package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class UvTest {

    @TempDir
    lateinit var home: Path

    @BeforeEach
    fun posixOnly() {
        assumeTrue(HostOs.current() != HostOs.WINDOWS)
    }

    @Test
    fun `the PATH comes first`() {
        val onPath = executable(home.resolve("bin"))
        executable(home.resolve(".local/bin"))
        assertEquals(onPath, Uv.locate(home.resolve("bin").toString(), home, { null }, emptyList()))
    }

    @Test
    fun `the installer's directories are found when the IDE's PATH lacks them`() {
        val installed = executable(home.resolve(".local/bin"))
        assertEquals(installed, Uv.locate(home.resolve("empty").toString(), home, { null }, emptyList()))

        val older = executable(home.resolve("old/.cargo/bin"))
        assertEquals(older, Uv.locate("", home.resolve("old"), { null }, emptyList()))
    }

    @Test
    fun `Homebrew's prefix is looked in`() {
        val brew = executable(home.resolve("homebrew/bin"))
        assertEquals(brew, Uv.locate("", home.resolve("nobody"), { null }, listOf(home.resolve("homebrew/bin"))))
    }

    @Test
    fun `nothing found is null`() {
        assertNull(Uv.locate("", home, { null }, emptyList()))
    }

    private fun executable(dir: Path): Path =
        dir.createDirectories().resolve("uv").apply {
            writeText("#!/bin/sh\n")
            toFile().setExecutable(true)
        }
}
