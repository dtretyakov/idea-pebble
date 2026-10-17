package com.github.dtretyakov.pebble.project

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class PebbleProjectLoadTest {

    @TempDir
    lateinit var root: Path

    @Test
    fun `a project waf has never configured needs loading`() {
        assertTrue(PebbleProjectLoad.needsLoad(root, "emery"))
    }

    @Test
    fun `waf's cache for the platform is enough, whatever the build made of the code`() {
        root.resolve("build/c4che").createDirectories().resolve("emery_cache.py").writeText("")
        assertFalse(PebbleProjectLoad.needsLoad(root, "emery"))
        // Configured for one platform is not configured for another.
        assertTrue(PebbleProjectLoad.needsLoad(root, "gabbro"))
    }
}
