package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.LocalToolHost
import com.github.dtretyakov.pebble.sdk.ToolHost
import com.github.dtretyakov.pebble.sdk.ToolHostState
import com.github.dtretyakov.pebble.sdk.WslDistributions
import com.github.dtretyakov.pebble.sdk.WslPaths
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.util.EnvironmentUtil
import org.jetbrains.annotations.TestOnly
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Which machine the tools run on, for the whole IDE: this one on a Mac or Linux; on Windows, a WSL
 * distribution — the one Settings names, else the one the open project lives in, else WSL's
 * default.
 *
 * One for the application rather than one per project, as the SDK is: pebble-tool, its SDKs and
 * its emulators are the machine's, and every project shares them.
 *
 * Nothing here waits for WSL on the caller's thread. The distributions are listed, and the chosen
 * one asked where its tools are, in the background; until then [state] says so, and when the
 * answer arrives everything that reads the environment is told ([announce]).
 */
object ToolHosts {

    private val local: LocalToolHost by lazy { LocalToolHost(environment = EnvironmentUtil::getValue) }

    @Volatile
    private var distributions: List<WslDistributions.Distribution>? = null

    private val hosts = ConcurrentHashMap<Pair<String, String>, WslToolHost>()
    private val listing = AtomicBoolean(false)
    private val probing: MutableSet<WslToolHost> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var hostForTests: ToolHost? = null

    /** Where the tools run; null on Windows until WSL has a distribution to run them in. */
    fun current(): ToolHost? {
        hostForTests?.let { return it }
        if (HostOs.current() != HostOs.WINDOWS) return local
        return wsl()
    }

    /**
     * [current], or this machine where there is no other: Windows without WSL, whose tools are
     * none anyway. On Windows the distributions are listed first if they have not been — once, and
     * quickly: `wsl --list` does not start WSL's machine — since this machine's spelling of a path
     * handed to a tool in WSL would be wrong, not merely late.
     */
    fun currentOrLocal(): ToolHost {
        current()?.let { return it }
        if (HostOs.current() == HostOs.WINDOWS && hostForTests == null && distributions == null && ApplicationManager.getApplication() != null) {
            listNow()
            current()?.let { return it }
        }
        return local
    }

    /**
     * Where a command is to run. On Windows never this machine, which has no SDK: WSL's
     * distribution, or a failure that says so.
     */
    fun forCommands(): ToolHost {
        current()?.let { return it }
        if (HostOs.current() != HostOs.WINDOWS) return local
        // A command is about to run, whichever thread asks: a moment's wait for wsl.exe — once,
        // the first time — rather than a failure.
        if (distributions == null) listNow()
        return wsl() ?: throw com.intellij.execution.ExecutionException(
            "The Pebble SDK runs on Windows inside WSL, which has no distribution here yet: ${PebbleEnvironment.INSTALL_WSL}.",
        )
    }

    /**
     * The chosen distribution as a host. Asked in the background the first time — or, on a thread
     * that may wait and holds no lock the UI needs, right away: a command about to run, the first
     * thing after the IDE starts, wants the answer rather than a "not yet".
     */
    fun wsl(): WslToolHost? {
        val installed = distributions ?: if (canWait()) listNow() else run { listLater(); return null }
        val name = chosen(installed) ?: return null
        val prefix = projectRoots().mapNotNull { WslPaths.parseUnc(it) }
            .firstOrNull { it.distribution.equals(name, ignoreCase = true) }?.prefix ?: WslPaths.DEFAULT_PREFIX
        val host = hosts.computeIfAbsent(name to prefix) { WslToolHost(name, prefix) }
        if (host.probe == null && host.problem == null) {
            if (canWait()) synchronized(host) { if (host.probe == null && host.problem == null) probe(host) } else probeLater(host)
        }
        return host
    }

    /** Not the UI thread, nor under a read action, where a few hundred milliseconds of wsl.exe would hold up the IDE. */
    private fun canWait(): Boolean {
        val application = ApplicationManager.getApplication() ?: return false
        return !application.isDispatchThread && !application.isReadAccessAllowed
    }

    private val listLock = Any()

    private fun listNow(): List<WslDistributions.Distribution> {
        var listed = false
        val result = synchronized(listLock) {
            distributions ?: readDistributions().also {
                distributions = it
                listed = true
            }
        }
        // Outside the lock: the listeners read the environment, and another thread may be waiting on it.
        if (listed) announce()
        return result
    }

    private fun readDistributions(): List<WslDistributions.Distribution> =
        (if (Files.isRegularFile(Path.of(WslDistributions.EXE))) WslDistributions.list() ?: emptyList() else emptyList())
            .also { LOG.info("WSL distributions: ${it.joinToString { distribution -> distribution.name }}") }

    /** The installed distributions' names, for Settings; empty until listed. */
    fun distributionNames(): List<String> = distributions.orEmpty().map { it.name }

    /** The one used when Settings names none. */
    fun automaticDistribution(): String? = distributions?.let { automatic(it) }

    /** What the environment checklist says about the host. */
    fun state(): ToolHostState {
        if (hostForTests != null || HostOs.current() != HostOs.WINDOWS) return ToolHostState.Local
        val installed = distributions ?: run {
            listLater()
            return ToolHostState.Listing
        }
        val host = wsl() ?: return ToolHostState.NoWsl
        val elsewhere = projectRoots().mapNotNull(WslPaths::distributionOf)
            .firstOrNull { other -> !other.equals(host.distribution, ignoreCase = true) && installed.any { it.name.equals(other, ignoreCase = true) } }
        return ToolHostState.Wsl(host.distribution, ready = host.probe != null, problem = host.problem.takeIf { host.probe == null }, projectDistribution = elsewhere)
    }

    /**
     * Asks again, in the background: the distributions, and where the tools are. For when the
     * user may have changed the machine — the IDE regains focus, a terminal command has run.
     */
    fun refreshLater() {
        if (hostForTests != null || HostOs.current() != HostOs.WINDOWS) return
        if (distributions == null) return listLater()
        (ApplicationManager.getApplication() ?: return).executeOnPooledThread { refreshNow() }
    }

    /**
     * Asks again now, on this thread — never the UI thread. After something the plugin itself ran
     * that changes what the tools are: Set Up, an SDK installed or activated.
     */
    fun refreshNow() {
        if (hostForTests != null || HostOs.current() != HostOs.WINDOWS) return
        val listed = readDistributions()
        val known = distributions != null
        if (listed != distributions) {
            distributions = listed
            announce()
        }
        // A host met for the first time was just asked by wsl() itself.
        val host = wsl() ?: return
        if (known) probe(host)
    }

    /** After the setting changes: the next [current] is the new choice. */
    fun reset() {
        hosts.clear()
        announce()
        refreshLater()
    }

    private fun chosen(installed: List<WslDistributions.Distribution>): String? {
        val configured = PebbleAppSettings.getInstance().wslDistribution.trim()
        installed.firstOrNull { it.name.equals(configured, ignoreCase = true) }?.let { return it.name }
        return automatic(installed)
    }

    private fun automatic(installed: List<WslDistributions.Distribution>): String? {
        projectRoots().mapNotNull(WslPaths::distributionOf).forEach { name ->
            installed.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.name }
        }
        // WSL 1 shares Windows' network rather than forwarding it, and runs everything else alike.
        return (installed.firstOrNull { it.isDefault } ?: installed.firstOrNull())?.name
    }

    /** The open projects' directories, as Windows paths: where a project is says which distribution it wants. */
    private fun projectRoots(): List<String> = runCatching {
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.mapNotNull { it.basePath }
    }.getOrDefault(emptyList())

    private fun listLater() {
        val application = ApplicationManager.getApplication() ?: return
        if (!listing.compareAndSet(false, true)) return
        application.executeOnPooledThread {
            try {
                listNow()
            } finally {
                listing.set(false)
            }
            wsl()
        }
    }

    private fun probeLater(host: WslToolHost) {
        val application = ApplicationManager.getApplication() ?: return
        if (!probing.add(host)) return
        application.executeOnPooledThread {
            try {
                probe(host)
            } finally {
                probing.remove(host)
            }
        }
    }

    private fun probe(host: WslToolHost) {
        val settings = PebbleAppSettings.getInstance()
        val changed = host.refresh(settings.pebbleToolPath.trim(), settings.clangdPath.trim())
        host.problem?.let { LOG.info("WSL ${host.distribution} did not answer: $it") }
        if (!changed) return
        announce()
        // The emulator is the part of the SDK that needs the distribution's libraries: asked as
        // soon as the SDK is known, so a missing one is said before the first Run rather than by it.
        EmulatorCheck.getInstance().refresh(PebbleSdkService.getInstance().currentSdk())
    }

    /** Everything that reads the tools: the checklist and its banners, the SDK list, the libraries. */
    private fun announce() {
        val application = ApplicationManager.getApplication() ?: return
        if (application.isDisposed) return
        application.messageBus.syncPublisher(PebbleSdkService.TOPIC).sdksChanged()
        application.messageBus.syncPublisher(PebbleEnvironment.TOPIC).changed()
    }

    /** [host] in place of this machine's, until [disposable] goes. */
    @TestOnly
    fun useHost(host: ToolHost, disposable: Disposable) {
        hostForTests = host
        Disposer.register(disposable) { hostForTests = null }
    }

    private val LOG = logger<ToolHosts>()
}
