package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PebbleSdksTest {

    @TempDir
    lateinit var dataDir: Path

    @Test
    fun `no data directory, no SDKs`() {
        assertEquals(PebbleSdks.NONE.installed, PebbleSdks.scan(null).installed)
        assertEquals(PebbleSdks.NONE.installed, PebbleSdks.scan(dataDir).installed)
    }

    @Test
    fun `installed SDKs are listed newest first, by number rather than by text`() {
        install("4.9.148")
        install("4.33.1")
        install("4.4")

        assertEquals(listOf("4.33.1", "4.9.148", "4.4"), PebbleSdks.scan(dataDir).installed.map { it.version })
    }

    @Test
    fun `a directory without a readable manifest is not an SDK`() {
        install("4.33.1")
        dataDir.resolve("SDKs/half-downloaded/sdk-core").createDirectories()
        dataDir.resolve("SDKs/broken/sdk-core").createDirectories().resolve("manifest.json").writeText("{")

        assertEquals(listOf("4.33.1"), PebbleSdks.scan(dataDir).installed.map { it.version })
    }

    @Test
    fun `the current link names the active SDK and is not listed itself`() {
        install("4.9.148")
        val active = install("4.33.1")
        assumeTrue(link(dataDir.resolve("SDKs/current"), active), "symbolic links are not available here")

        val sdks = PebbleSdks.scan(dataDir)
        assertEquals("4.33.1", sdks.current?.version)
        assertEquals(2, sdks.installed.size)
    }

    @Test
    fun `no current link, no active SDK`() {
        install("4.33.1")
        assertNull(PebbleSdks.scan(dataDir).current)
    }

    @Test
    fun `an SDK knows its platforms and its toolchain`() {
        val root = install("4.33.1")
        listOf("emery", "basalt", "gabbro").forEach {
            root.resolve("sdk-core/pebble/$it/include").createDirectories().resolve("pebble.h").writeText("")
        }
        root.resolve("sdk-core/pebble/common").createDirectories()

        val sdk = PebbleSdk.at(root)!!
        assertEquals(listOf("basalt", "emery", "gabbro"), sdk.platforms())
        assertEquals("release", sdk.channel)
        assertFalse(sdk.hasCompiler)
        assertFalse(sdk.hasAlloyTools)

        root.resolve("toolchain/arm-none-eabi/bin").createDirectories()
        assertTrue(sdk.hasCompiler)
    }

    @Test
    fun `versions order numerically`() {
        assertTrue(SdkVersion.of("4.33.1") > SdkVersion.of("4.9.148"))
        assertTrue(SdkVersion.of("4.33") < SdkVersion.of("4.33.1"))
        assertEquals(0, SdkVersion.of("4.33").compareTo(SdkVersion.of("4.33.0")))
    }

    private fun install(version: String): Path {
        val root = dataDir.resolve("SDKs").resolve(version)
        root.resolve("sdk-core").createDirectories().resolve("manifest.json")
            .writeText("""{"version": "$version", "channel": "release", "requirements": []}""")
        return root
    }

    private fun link(link: Path, target: Path): Boolean =
        runCatching { Files.createSymbolicLink(link, target) }.isSuccess
}
