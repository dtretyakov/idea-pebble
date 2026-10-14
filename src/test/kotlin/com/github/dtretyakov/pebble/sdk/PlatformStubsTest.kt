package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PlatformStubsTest {

    /** Lines as aplite's `pebble.h` in SDK 4.33.1 has them. */
    private val header = """
        //! Maximum number of notes in a single speaker_play_notes() call.
        #define SPEAKER_MAX_NOTES 256

        #define speaker_play_notes(...) (0)

        #define persist_get_max_size(...) (4096)
        #define health_service_register_metric_alert(...) (NULL)
        #define app_glance_reload(...) do {} while(0)
        #define PBL_IF_COLOR_ELSE(if_true, if_false) (if_false)
        bool light_is_on(void);
    """.trimIndent()

    @Test
    fun `stubs are the macros that take any arguments`() {
        assertEquals(
            mapOf(
                "speaker_play_notes" to "(0)",
                "persist_get_max_size" to "(4096)",
                "health_service_register_metric_alert" to "(NULL)",
                "app_glance_reload" to "do {} while(0)",
            ),
            PlatformStubs.parse(header),
        )
    }

    @Test
    fun `a stub that returns an answer is not inert`() {
        assertTrue(PlatformStubs.isInert("(0)"))
        assertTrue(PlatformStubs.isInert("do {} while(0)"))
        assertFalse(PlatformStubs.isInert("(4096)"))
        assertFalse(PlatformStubs.isInert("(PreferredContentSizeMedium)"))
    }

    @Test
    fun `an SDK's inert stubs per platform`() {
        val root = Files.createTempDirectory("sdk")
        root.resolve("sdk-core/pebble/aplite/include").createDirectories().resolve("pebble.h").writeText(header)
        val sdk = PebbleSdk(root, "4.33.1", null)
        assertEquals(setOf("speaker_play_notes", "health_service_register_metric_alert", "app_glance_reload"), PlatformStubs.inert(sdk, "aplite"))
        assertEquals(emptySet<String>(), PlatformStubs.inert(sdk, "emery"))
    }
}
