package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PebbleDataDirTest {

    @TempDir
    lateinit var home: Path

    @Test
    fun `macOS keeps it in Application Support`() {
        assertEquals(
            home.resolve("Library/Application Support/Pebble SDK"),
            PebbleDataDir.locate(HostOs.MAC, home) { null },
        )
    }

    @Test
    fun `Linux prefers the legacy directory when it exists`() {
        Files.createDirectories(home.resolve(".pebble-sdk"))
        assertEquals(home.resolve(".pebble-sdk"), PebbleDataDir.locate(HostOs.LINUX, home) { "/elsewhere" })
    }

    @Test
    fun `Linux follows XDG_DATA_HOME otherwise`() {
        assertEquals(
            Path.of("/xdg/pebble-sdk"),
            PebbleDataDir.locate(HostOs.LINUX, home) { if (it == "XDG_DATA_HOME") "/xdg" else null },
        )
    }

    @Test
    fun `Linux falls back to the XDG default`() {
        assertEquals(home.resolve(".local/share/pebble-sdk"), PebbleDataDir.locate(HostOs.LINUX, home) { "" })
    }

    @Test
    fun `Windows has none`() {
        assertNull(PebbleDataDir.locate(HostOs.WINDOWS, home) { null })
    }

    @Test
    fun `os names map to the systems pebble-tool tells apart`() {
        assertEquals(HostOs.MAC, HostOs.of("Mac OS X"))
        assertEquals(HostOs.WINDOWS, HostOs.of("Windows 11"))
        assertEquals(HostOs.LINUX, HostOs.of("Linux"))
        assertEquals(HostOs.LINUX, HostOs.of("FreeBSD"))
    }
}
