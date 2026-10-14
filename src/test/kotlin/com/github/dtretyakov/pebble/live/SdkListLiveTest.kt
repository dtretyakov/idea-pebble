package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdkList
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

/** The versions pebble-tool offers, as the Install button reads them. Needs the download server. */
class SdkListLiveTest {

    @Test
    fun `available versions are read and exclude what is installed`() {
        val output = LiveSdk.run(Path.of("."), "sdk", "list").text
        val available = PebbleSdkList.available(output)
        assertTrue(available.isNotEmpty(), output)
        val installed = PebbleSdks.scan(PebbleDataDir.locate()).installed.map { it.version }
        assertTrue(available.none { it in installed }, "$available overlaps $installed")
    }
}
