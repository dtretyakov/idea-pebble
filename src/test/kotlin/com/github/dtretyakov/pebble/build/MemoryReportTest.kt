package com.github.dtretyakov.pebble.build

import com.github.dtretyakov.pebble.run.HeapLeakHint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryReportTest {

    /** As `pebble build` printed it for the C fixture, SDK 4.33.1. */
    private val output = """
        [16/16] Processing gabbro | memory_usage_report: build/gabbro/pebble-app.elf build/gabbro/app_resources.pbpack
        -------------------------------------------------------
        GABBRO APP MEMORY USAGE
        Total size of resources:        4092 bytes / 256.0KB
        Total footprint in RAM:         220 bytes / 128.0KB
        Free RAM available (heap):      130852 bytes
        -------------------------------------------------------
        [17/29] Creating flint | resource_ball:  -> build/flint/system_resources.resball
        -------------------------------------------------------
        FLINT WORKER MEMORY USAGE
        Total footprint in RAM:         1100 bytes / 10.0KB
        Free RAM available (heap):      9140 bytes
        -------------------------------------------------------
    """.trimIndent()

    private fun parse(text: String): List<MemoryUsage> {
        val parser = MemoryReportParser()
        return text.lines().mapNotNull(parser::parseLine)
    }

    @Test
    fun `each platform's report is read whole`() {
        val (app, worker) = parse(output)
        assertEquals(MemoryUsage("gabbro", "app", 220, 128 * 1024, 130852, 4092, 256 * 1024), app)
        assertEquals(MemoryUsage("flint", "worker", 1100, 10 * 1024, 9140, null, null), worker)
        assertFalse(app.isTight)
        assertEquals("Memory: 220 bytes in RAM of 128.0 KB, 127.8 KB free for the heap; resources 4.0 KB of the store's 256.0 KB", app.summary)
        assertTrue(worker.summary.startsWith("Worker memory: "))
    }

    @Test
    fun `little heap, or resources over the store's limit, is tight`() {
        assertTrue(MemoryUsage("aplite", "app", 22000, 24 * 1024, 2000, null, null).isTight)
        assertTrue(MemoryUsage("aplite", "app", 200, 24 * 1024, 24000, 300 * 1024, 256 * 1024).isTight)
        assertFalse(MemoryUsage("aplite", "app", 200, null, null, null, null).isTight)
    }

    @Test
    fun `a library's report has no limits`() {
        val usage = parse("---------------------------------\nEMERY MEMORY USAGE\nTotal footprint in RAM:         512 bytes\n---------------------------------").single()
        assertEquals(MemoryUsage("emery", "app", 512, null, null, null, null), usage)
    }

    @Test
    fun `the firmware's heap line at exit`() {
        val line = "[11:41:56] ocess_manager.c:487> Heap Usage for App <Fixture C>: Total Size <130848B> Used <24B> Still allocated <0B>"
        assertEquals(HeapUsage("Fixture C", 130848, 24, 0), HeapUsage.parse(line))
        assertNull(HeapUsage.parse("[11:41:56] main.c:12> Heap"))
        assertEquals(
            "Leaky exited with 100 bytes still allocated: memory it never freed.\n",
            HeapLeakHint.text(HeapUsage("Leaky", 1, 1, 100)),
        )
    }
}
