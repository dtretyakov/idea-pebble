package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.PebbleTool
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** What the plugin reads from disk, against what pebble-tool itself says. */
class SdkLiveTest {

    @Test
    fun `the active SDK is the one pebble-tool reports`() {
        val answer = LiveSdk.run(Path.of("."), "--version").text
        assertTrue(PebbleTool.parseVersion(answer) != null, answer)
        val active = Regex("""active SDK: v(\S+)\)""").find(answer)?.groupValues?.get(1)
        assertEquals(active, PebbleSdks.scan(PebbleDataDir.locate()).current?.version)
    }

    @Test
    fun `every installed SDK pebble-tool lists is found`() {
        val listed = LiveSdk.run(Path.of("."), "sdk", "list").text
            .substringAfter("Installed SDKs:").substringBefore("Available SDKs:")
            .lines().map { it.trim().substringBefore(" ") }.filter { it.isNotEmpty() }.toSet()
        val found = PebbleSdks.scan(PebbleDataDir.locate()).installed.map { it.version }.toSet()
        assertEquals(listed, found)
    }

    @Test
    fun `the SDK ships a pebble h for every platform it reports`() {
        // First: it is what skips the test where live tests are off, as on CI.
        LiveSdk.tool()
        val sdk = PebbleSdks.scan(PebbleDataDir.locate()).current ?: error("no active SDK")
        assertTrue(sdk.platforms().containsAll(listOf("emery", "gabbro", "flint")), sdk.platforms().toString())
        assertTrue(sdk.hasCompiler)
        assertTrue(sdk.hasAlloyTools)
    }
}
