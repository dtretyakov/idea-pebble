package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.project.CompileDatabase
import com.github.dtretyakov.pebble.sdk.HostTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

/**
 * The database pebble-tool writes, read by clangd the way the plugin starts it: with the SDK's gcc
 * as query driver, a Pebble source checks clean — `pebble.h` and newlib's headers both found.
 */
class ClangdLiveTest {

    @Test
    fun `clangd reads the compile database without errors`() {
        val root = LiveSdk.fixture("fixture-c")
        LiveSdk.tool()
        val host = LiveSdk.host
        val clangd = host.locate(HostTool.CLANGD)
        assumeTrue(clangd != null, "clangd is not installed")
        assertEquals(0, LiveSdk.run(root, "build").exitCode)
        assertEquals(0, LiveSdk.run(root, "compile-commands", "--platform", "gabbro").exitCode)
        assertEquals("gabbro", CompileDatabase.platformIn(root.resolve(CompileDatabase.FILE_NAME).readText()))

        // As the plugin starts it: where the tools are, with their spelling of every path.
        val sdk = LiveSdk.sdks().current!!
        val spell = { path: java.nio.file.Path -> host.toolPath(path) ?: path.toString() }
        val output = LiveSdk.exec(
            clangd!!,
            listOf(
                "--compile-commands-dir=${spell(root)}",
                "--query-driver=${sdk.compilerGlob(host::toolPath)}",
                "--check=${spell(root.resolve("src/c/fixture-c.c"))}",
            ),
            root,
        )
        assertTrue(output.text.contains("All checks completed, 0 errors"), output.text.takeLast(2000))
    }
}
