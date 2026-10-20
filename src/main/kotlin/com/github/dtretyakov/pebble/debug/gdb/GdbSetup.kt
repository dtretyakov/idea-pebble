package com.github.dtretyakov.pebble.debug.gdb

import com.github.dtretyakov.pebble.emulator.RunningEmulator
import com.github.dtretyakov.pebble.project.ToolCommandLine
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString

/**
 * gdb attached to the emulator the way `pebble gdb` attaches it (pebble-tool's
 * `commands/sdk/project/debug.py`), but speaking MI:
 *
 * - the SDK's `arm-none-eabi-gdb` on the firmware's debug ELF,
 *   `sdk-core/pebble/<platform>/qemu/<platform>_sdk_debug.elf`;
 * - `target remote` on QEMU's gdb stub, whose port pebble-tool keeps in `pb-emulator.json`;
 * - the app's ELF, `build/<platform>/pebble-app.elf`, added at the address the firmware loaded it
 *   to, `*(void**)&g_app_load_address`, each allocated section at its offset from `objdump`;
 *   likewise the worker's, if the app has one;
 * - a breakpoint on `app_crashed`, where the firmware sends an app that crashes.
 *
 * `mi-async` lets the debugger interrupt a running app; without it gdb ignores `-exec-interrupt`.
 */
class GdbSetup(
    val gdb: Path,
    val firmwareElf: Path,
    val port: Int,
    val symbolCommands: List<String>,
    /** A source or an ELF as gdb, where the tools run, spells it: under WSL a Linux path. */
    val spell: (Path) -> String = DEFAULT_SPELL,
    /** A path gdb wrote — a frame's `fullname` — as the IDE spells it. */
    val unspell: (String) -> Path? = DEFAULT_UNSPELL,
) {

    /** Run where the tools are: under WSL, through wsl.exe; gdb's machine interface goes through its pipes untouched. */
    fun commandLine(host: ToolHost = ToolCommandLine.host()): GeneralCommandLine =
        ToolCommandLine.build(gdb, listOf("--interpreter=mi3", "-q", spell(firmwareElf)), host = host)

    fun commands(): List<String> = buildList {
        add("-gdb-set confirm off")
        add("-gdb-set mi-async on")
        // pebble-tool's workaround for the old libiconv on macOS.
        if (SystemInfo.isMac) add("-gdb-set charset US-ASCII")
        add("-target-select remote :$port")
        symbolCommands.forEach { add(GdbCommands.console(it)) }
        add("-break-insert $CRASH_FUNCTION")
    }

    class Problem(message: String) : Exception(message)

    companion object {
        const val APP_LOAD_ADDRESS = "*(void**)&g_app_load_address"
        const val WORKER_LOAD_ADDRESS = "*(void**)&g_worker_load_address"
        const val CRASH_FUNCTION = "app_crashed"

        val DEFAULT_SPELL: (Path) -> String = { it.invariantSeparatorsPathString }
        val DEFAULT_UNSPELL: (String) -> Path? = { runCatching { Path.of(it) }.getOrNull() }

        fun firmwareElf(sdk: PebbleSdk, platform: String): Path =
            sdk.core.resolve("pebble").resolve(platform).resolve("qemu").resolve("${platform}_sdk_debug.elf")

        fun appElf(root: Path, platform: String): Path = root.resolve("build").resolve(platform).resolve("pebble-app.elf")

        fun workerElf(root: Path, platform: String): Path = root.resolve("build").resolve(platform).resolve("pebble-worker.elf")

        /** [prepare] for gdb where [host] runs it: under WSL every path given to it is a Linux one, and every one it gives back too. */
        fun prepare(host: ToolHost, sdks: PebbleSdks, root: Path, platform: String, emulators: List<RunningEmulator>): GdbSetup =
            if (host is LocalToolHost) {
                prepare(sdks, root, platform, emulators)
            } else {
                prepare(sdks, root, platform, emulators, spell = { path -> host.toolPath(path) ?: path.toString() }, unspell = host::idePath)
            }

        /** Everything gdb needs, or what is missing, in pebble-tool's words where it has them. */
        fun prepare(
            sdks: PebbleSdks,
            root: Path,
            platform: String,
            emulators: List<RunningEmulator>,
            spell: (Path) -> String = DEFAULT_SPELL,
            unspell: (String) -> Path? = DEFAULT_UNSPELL,
            objdump: (Path, Path) -> String = { tool, elf -> objdump(tool, elf, spell) },
        ): GdbSetup {
            val emulator = emulators.firstOrNull { it.platform == platform }
                ?: throw Problem("No $platform emulator is running.")
            val port = emulator.gdbPort ?: throw Problem("The emulator does not have gdb support. Try killing and re-running it.")
            val sdk = sdks.installed.firstOrNull { it.version == emulator.sdkVersion } ?: sdks.current
                ?: throw Problem("SDK ${emulator.sdkVersion}, which runs the emulator, is not installed.")
            val gdb = sdk.compilerBin.resolve("arm-none-eabi-gdb")
            if (!Files.isRegularFile(gdb)) throw Problem("$sdk has no arm-none-eabi-gdb in ${sdk.compilerBin}.")
            val firmware = firmwareElf(sdk, platform)
            if (!Files.isRegularFile(firmware)) throw Problem("$sdk does not support app debugging on $platform: there is no $firmware.")
            val app = appElf(root, platform)
            if (!Files.isRegularFile(app)) throw Problem("No app debugging information available: build the app for $platform first.")
            val tool = sdk.compilerBin.resolve("arm-none-eabi-objdump")
            val symbols = listOf(app to APP_LOAD_ADDRESS, workerElf(root, platform) to WORKER_LOAD_ADDRESS)
                .filter { Files.isRegularFile(it.first) }
                .map { (elf, base) -> symbolCommand(elf, base, sectionOffsets(objdump(tool, elf)), spell) }
            return GdbSetup(gdb, firmware, port, symbols, spell, unspell)
        }

        /** The allocated sections of `objdump --headers --wide`, by name, at their offsets in the image. */
        fun sectionOffsets(headers: String): Map<String, Long> {
            val offsets = LinkedHashMap<String, Long>()
            for (row in headers.lines()) {
                val parts = row.trim().split(Regex("\\s+"), limit = 8)
                if (parts.size < 8 || parts[0].toIntOrNull() == null || "ALLOC" !in parts[7]) continue
                offsets[parts[1]] = parts[3].toLongOrNull(16) ?: continue
            }
            return offsets
        }

        /** `add-symbol-file "<elf>" <base>+<.text> -s <section> <base>+<offset> …`, as pebble-tool writes it. */
        fun symbolCommand(elf: Path, base: String, offsets: Map<String, Long>, spell: (Path) -> String = DEFAULT_SPELL): String {
            val text = offsets[".text"] ?: throw Problem("$elf has no .text section.")
            return buildString {
                append("add-symbol-file \"").append(spell(elf)).append("\" ").append(base).append("+0x").append(text.toString(16))
                for ((section, offset) in offsets) {
                    if (section != ".text") append(" -s ").append(section).append(' ').append(base).append("+0x").append(offset.toString(16))
                }
            }
        }

        private fun objdump(tool: Path, elf: Path, spell: (Path) -> String): String {
            val output = ExecUtil.execAndGetOutput(ToolCommandLine.build(tool, listOf("--headers", "--wide", spell(elf))))
            if (output.exitCode != 0) throw Problem("objdump could not read $elf: ${output.stderr.trim()}")
            return output.stdout
        }
    }
}
