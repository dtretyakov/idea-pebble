package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.sdk.HostTool
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.util.messages.Topic
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path

/**
 * The one place that answers "where is pebble-tool, and which SDKs has it installed".
 *
 * The SDK is managed outside the IDE — `pebble sdk install` and `pebble sdk activate` can run in a
 * terminal at any moment — so nothing here is remembered beyond the point it could have changed.
 * The installed SDKs are re-read when the `SDKs` directory or its `current` link moves, which is a
 * pair of stats per lookup rather than a directory walk.
 */
@Service(Service.Level.APP)
class PebbleSdkService {

    private class Snapshot(val stamp: List<Any?>, val sdks: PebbleSdks)

    @Volatile
    private var snapshot: Snapshot? = null

    /** Where the tools run: this machine, or on Windows a WSL distribution ([ToolHosts]). */
    fun host(): ToolHost? = ToolHosts.current()

    /** The `pebble` executable, as the IDE spells it, or null when it cannot be found. */
    fun tool(): Path? = host()?.locate(HostTool.PEBBLE, PebbleAppSettings.getInstance().pebbleToolPath)

    @Volatile
    private var dataDirForTests: Path? = null

    /** Where pebble-tool keeps its SDKs, as the IDE reads it; null on Windows until WSL has a distribution. */
    fun dataDir(): Path? = dataDirForTests ?: host()?.let(PebbleDataDir::locate)

    /** An SDK laid out on disk by a test, in place of this machine's, until [disposable] goes. */
    @TestOnly
    fun useDataDir(dataDir: Path, disposable: Disposable) {
        dataDirForTests = dataDir
        Disposer.register(disposable) { dataDirForTests = null }
    }

    fun sdks(): PebbleSdks {
        val dataDir = dataDir()
        val host = if (dataDirForTests != null) null else host()
        val resolveLink: (Path) -> Path? = host?.let { it::resolveLink } ?: { link -> runCatching { link.toRealPath() }.getOrNull() }
        val stamp = stampOf(dataDir, resolveLink)
        snapshot?.let { if (it.stamp == stamp) return it.sdks }
        return PebbleSdks.scan(dataDir, resolveLink).also { snapshot = Snapshot(stamp, it) }
    }

    /** The active SDK — the one `pebble build` would use. */
    fun currentSdk(): PebbleSdk? = sdks().current

    private fun stampOf(dataDir: Path?, resolveLink: (Path) -> Path?): List<Any?> {
        val sdks = dataDir?.resolve("SDKs") ?: return listOf(null)
        return listOf(
            sdks,
            runCatching { Files.getLastModifiedTime(sdks) }.getOrNull(),
            resolveLink(sdks.resolve("current")),
        )
    }

    /** Told when pebble-tool has installed, activated or removed an SDK at the plugin's request. */
    fun interface Listener {
        fun sdksChanged()
    }

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<Listener> = Topic.create("Pebble SDKs", Listener::class.java)

        fun getInstance(): PebbleSdkService = service()
    }
}
