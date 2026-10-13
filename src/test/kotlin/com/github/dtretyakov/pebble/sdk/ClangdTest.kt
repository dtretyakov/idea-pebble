package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class ClangdTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `a plain clangd is preferred to a versioned one, and the newest versioned one to older`() {
        assumeTrue(HostOs.current() != HostOs.WINDOWS)
        val bin = dir.resolve("bin").createDirectories()
        executable(bin.resolve("clangd-16"))
        executable(bin.resolve("clangd-18"))
        assertEquals(bin.resolve("clangd-18"), Clangd.locate("", bin.toString()))
        executable(bin.resolve("clangd"))
        assertEquals(bin.resolve("clangd"), Clangd.locate("", "/nowhere${File.pathSeparator}$bin"))
    }

    @Test
    fun `a configured path is used as given`() {
        assumeTrue(HostOs.current() != HostOs.WINDOWS)
        val custom = executable(dir.resolve("custom-clangd"))
        assertEquals(custom, Clangd.locate(custom.toString(), null))
        assertNull(Clangd.locate(dir.resolve("missing").toString(), null))
    }

    private fun executable(file: Path): Path {
        file.writeText("#!/bin/sh\n")
        file.toFile().setExecutable(true)
        return file
    }
}
