package com.github.dtretyakov.pebble.emulator

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * The emulators pebble-tool has running, from `pb-emulator.json` in the system temporary directory
 * — pebble-tool's own record, and the only way a second client can find a running emulator.
 *
 * ```
 * {"emery": {"4.33.1": {"qemu": {"pid": 4227, "port": 52583, "gdb": 58439, "monitor": 34721, "vnc": true},
 *                       "pypkjs": {"pid": 4233, "port": 49225}, …}}}
 * ```
 */
data class RunningEmulator(
    val platform: String,
    val sdkVersion: String,
    val qemuPid: Long,
    val vnc: Boolean,
    val gdbPort: Int?,
    val monitorPort: Int?,
    /** pypkjs, the phone half `pebble install` talks to: up only some seconds after QEMU. */
    val pypkjsPid: Long? = null,
    val pypkjsPort: Int? = null,
)

object EmulatorState {

    /** pebble-tool's display `:1`, the same for every emulator it starts with `--vnc`. */
    const val VNC_PORT = 5901

    fun file(): Path = Path.of(System.getProperty("java.io.tmpdir"), "pb-emulator.json")

    /** The emulators recorded in [file] whose QEMU is still alive. */
    fun running(file: Path = file(), isAlive: (Long) -> Boolean = ::isAlive): List<RunningEmulator> {
        if (!Files.isRegularFile(file)) return emptyList()
        return parse(runCatching { file.readText() }.getOrDefault("")).filter { isAlive(it.qemuPid) }
    }

    /** The one emulator with a VNC server — at most one, since they all take display `:1`. */
    fun withScreen(): RunningEmulator? = running().firstOrNull { it.vnc }

    fun parse(text: String): List<RunningEmulator> {
        val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return emptyList()
        return root.flatMap { (platform, versions) ->
            (versions as? JsonObject).orEmpty().mapNotNull { (version, entry) ->
                val qemu = (entry as? JsonObject)?.get("qemu") as? JsonObject ?: return@mapNotNull null
                val pypkjs = entry["pypkjs"] as? JsonObject
                RunningEmulator(
                    platform = platform,
                    sdkVersion = version,
                    qemuPid = qemu["pid"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null,
                    vnc = qemu["vnc"]?.jsonPrimitive?.booleanOrNull == true,
                    gdbPort = qemu["gdb"]?.jsonPrimitive?.intOrNull,
                    monitorPort = qemu["monitor"]?.jsonPrimitive?.intOrNull,
                    pypkjsPid = pypkjs?.get("pid")?.jsonPrimitive?.longOrNull,
                    pypkjsPort = pypkjs?.get("port")?.jsonPrimitive?.intOrNull,
                )
            }
        }
    }

    private fun isAlive(pid: Long): Boolean = ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
}
