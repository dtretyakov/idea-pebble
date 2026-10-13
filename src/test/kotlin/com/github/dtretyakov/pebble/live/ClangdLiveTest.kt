package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.project.CompileDatabase
import com.github.dtretyakov.pebble.sdk.Clangd
import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.io.path.readText

/**
 * The database pebble-tool writes, read by clangd the way the plugin starts it: with the SDK's gcc
 * as query driver, a Pebble source checks clean — `pebble.h` and newlib's headers both found.
 */
class ClangdLiveTest {

    @Test
    fun `clangd reads the compile database without errors`() {
        val root = LiveSdk.fixture("fixture-c")
        val clangd = Clangd.locate("", System.getenv("PATH"))
        assumeTrue(clangd != null, "clangd is not installed")
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
        assertEquals(0, LiveSdk.run(root, "compile-commands", "--platform", "gabbro").exitCode)
        assertEquals("gabbro", CompileDatabase.platformIn(root.resolve(CompileDatabase.FILE_NAME).readText()))

        val sdk = PebbleSdks.scan(PebbleDataDir.locate()).current!!
        val process = ProcessBuilder(
            clangd.toString(),
            "--compile-commands-dir=$root",
            "--query-driver=${sdk.compilerGlob}",
            "--check=${root.resolve("src/c/fixture-c.c")}",
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(2, TimeUnit.MINUTES))
        assertTrue(output.contains("All checks completed, 0 errors"), output.takeLast(2000))
    }
}
