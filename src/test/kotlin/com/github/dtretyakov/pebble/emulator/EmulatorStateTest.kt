package com.github.dtretyakov.pebble.emulator

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class EmulatorStateTest {

    @TempDir
    lateinit var dir: Path

    /** As pebble-tool 5.0.40 writes it. */
    private val recorded = """
        {
            "emery": {"4.33.1": {"qemu": {"pid": 4227, "port": 52583, "serial": 60203, "gdb": 58439, "monitor": 34721, "vnc": true},
                                 "pypkjs": {"pid": 4233, "port": 49225}, "version": "4.33.1", "websockify": {"pid": 4237}}},
            "basalt": {"4.33.1": {"qemu": {"pid": 4100, "port": 1, "serial": 2, "gdb": 3, "monitor": 4, "vnc": false}}}
        }
    """.trimIndent()

    @Test
    fun `every recorded emulator is read`() {
        val emulators = EmulatorState.parse(recorded).associateBy { it.platform }
        assertEquals(RunningEmulator("emery", "4.33.1", 4227, vnc = true, gdbPort = 58439, monitorPort = 34721), emulators["emery"])
        assertEquals(false, emulators["basalt"]?.vnc)
    }

    @Test
    fun `only live ones count`() {
        val file = dir.resolve("pb-emulator.json").also { it.writeText(recorded) }
        assertEquals(listOf("emery"), EmulatorState.running(file) { it == 4227L }.map { it.platform })
    }

    @Test
    fun `no file or a broken one means none running`() {
        assertEquals(emptyList<RunningEmulator>(), EmulatorState.running(dir.resolve("missing.json")))
        assertEquals(emptyList<RunningEmulator>(), EmulatorState.parse("{"))
    }
}
