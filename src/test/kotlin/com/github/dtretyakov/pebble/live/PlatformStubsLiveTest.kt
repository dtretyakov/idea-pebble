package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.PlatformStubs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** What the SDK stubs out where: the speaker exists on the newer watches, and on no older one. */
class PlatformStubsLiveTest {

    @Test
    fun `the speaker is stubbed out on exactly the platforms without one`() {
        LiveSdk.tool()
        val sdk = PebbleSdks.scan(PebbleDataDir.locate()).current!!
        val stubbed = sdk.platforms().filter { "speaker_play_notes" in PlatformStubs.inert(sdk, it) }
        assertEquals(listOf("aplite", "basalt", "chalk", "diorite"), stubbed)
        assertTrue(sdk.platforms().none { "persist_get_max_size" in PlatformStubs.inert(sdk, it) })
    }
}
