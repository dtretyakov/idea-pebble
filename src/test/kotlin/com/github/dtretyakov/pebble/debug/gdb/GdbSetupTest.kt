package com.github.dtretyakov.pebble.debug.gdb

import com.github.dtretyakov.pebble.emulator.RunningEmulator
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile

class GdbSetupTest {

    private val headers = javaClass.getResource("/gdb/objdump-headers.txt")!!.readText()

    @Test
    fun `the allocated sections, at their offsets in the image`() {
        val offsets = GdbSetup.sectionOffsets(headers)
        assertEquals(listOf(".header", ".note.gnu.build-id", ".text", ".init", ".fini", ".data", ".bss"), offsets.keys.toList())
        assertEquals(0xa8L, offsets[".text"])
        assertEquals(0x2c8L, offsets[".bss"])
    }

    @Test
    fun `the symbol command is pebble-tool's, word for word`() {
        // As pebble gdb's debug.py wrote it for the same ELF, in the spike.
        assertEquals(
            "add-symbol-file \"/work/my app/build/emery/pebble-app.elf\" *(void**)&g_app_load_address+0xa8" +
                " -s .header *(void**)&g_app_load_address+0x0 -s .note.gnu.build-id *(void**)&g_app_load_address+0x82" +
                " -s .init *(void**)&g_app_load_address+0x2b4 -s .fini *(void**)&g_app_load_address+0x2b8" +
                " -s .data *(void**)&g_app_load_address+0x2bc -s .bss *(void**)&g_app_load_address+0x2c8",
            GdbSetup.symbolCommand(Path.of("/work/my app/build/emery/pebble-app.elf"), GdbSetup.APP_LOAD_ADDRESS, GdbSetup.sectionOffsets(headers)),
        )
    }

    private fun emulator(gdbPort: Int? = 38119) = RunningEmulator("emery", "4.33.1", 1, true, gdbPort, 2)

    private fun sdk(root: Path): PebbleSdk {
        val sdk = PebbleSdk(root.resolve("SDKs/4.33.1"), "4.33.1", null)
        sdk.compilerBin.createDirectories().resolve("arm-none-eabi-gdb").createFile()
        GdbSetup.firmwareElf(sdk, "emery").parent.createDirectories().resolve("emery_sdk_debug.elf").createFile()
        return sdk
    }

    @Test
    fun `gdb on the firmware, the emulator's port, the app's and the worker's symbols, and a stop where apps crash`(@TempDir temp: Path) {
        val sdk = sdk(temp)
        val app = temp.resolve("app")
        GdbSetup.appElf(app, "emery").parent.createDirectories()
        GdbSetup.appElf(app, "emery").createFile()
        GdbSetup.workerElf(app, "emery").createFile()
        val setup = GdbSetup.prepare(PebbleSdks(listOf(sdk), sdk), app, "emery", listOf(emulator())) { _, _ -> headers }

        assertEquals(listOf(sdk.compilerBin.resolve("arm-none-eabi-gdb").toString(), "--interpreter=mi3", "-q", GdbSetup.DEFAULT_SPELL(GdbSetup.firmwareElf(sdk, "emery"))), setup.commandLine(LocalToolHost()).getCommandLineList(null))
        val commands = setup.commands()
        assertEquals(listOf("-gdb-set confirm off", "-gdb-set mi-async on"), commands.take(2))
        assertTrue("-target-select remote :38119" in commands)
        val symbols = commands.filter { it.startsWith("-interpreter-exec console") }
        assertEquals(2, symbols.size)
        assertTrue(symbols[0].contains("pebble-app.elf") && symbols[0].contains(GdbSetup.APP_LOAD_ADDRESS))
        assertTrue(symbols[1].contains("pebble-worker.elf") && symbols[1].contains(GdbSetup.WORKER_LOAD_ADDRESS))
        assertEquals("-break-insert app_crashed", commands.last())
    }

    @Test
    fun `what is missing is said`(@TempDir temp: Path) {
        val sdk = sdk(temp)
        val sdks = PebbleSdks(listOf(sdk), sdk)
        val app = temp.resolve("app")
        fun problem(emulators: List<RunningEmulator>) =
            assertThrows<GdbSetup.Problem> { GdbSetup.prepare(sdks, app, "emery", emulators) { _, _ -> headers } }.message!!

        assertEquals("No emery emulator is running.", problem(emptyList()))
        assertEquals("The emulator does not have gdb support. Try killing and re-running it.", problem(listOf(emulator(gdbPort = null))))
        assertTrue(problem(listOf(emulator())).startsWith("No app debugging information available"))
    }
}
