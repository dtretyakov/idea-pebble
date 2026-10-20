package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.project.PebbleAppSettings
import com.github.dtretyakov.pebble.project.PebbleCommands
import com.github.dtretyakov.pebble.project.PebbleEnvironment
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Fix
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Status
import com.github.dtretyakov.pebble.project.PebbleSdkService
import com.github.dtretyakov.pebble.project.PebbleSdkSetup
import com.github.dtretyakov.pebble.project.ToolHosts
import com.github.dtretyakov.pebble.sdk.WslDistributions
import com.github.dtretyakov.pebble.sdk.WslProcesses
import com.github.dtretyakov.pebble.sdk.WslToolHost
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.progress.EmptyProgressIndicator
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.copyToRecursively

/**
 * Setting up from nothing on Windows, as a newcomer meets it: a WSL distribution fresh from
 * `wsl --install`, with its first user and nothing else. The checklist asks first for the system's
 * packages, in one command — typed here as the user would, with sudo's password — then offers Set
 * Up, which installs uv, pebble-tool and the latest SDK by itself; after which nothing is missing
 * and a project builds, on a Windows drive and inside the distribution.
 *
 * Changes the distribution it is given for good, so it runs only when one is named:
 * `PEBBLE_WSL_ONBOARDING=<distribution>`, a throwaway one —
 * `wsl --install Ubuntu-24.04 --name PebbleOnboarding --no-launch`, a user made with sudo.
 */
class OnboardingLiveTest : IdeTestCase() {

    /** Off the UI thread, as the setup task and the terminal are. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun runInDispatchThread(): Boolean = false

    fun `test a fresh distribution is set up from the checklist alone`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val distribution = System.getenv("PEBBLE_WSL_ONBOARDING")?.takeIf { it.isNotBlank() } ?: return
        val settings = PebbleAppSettings.getInstance()
        val saved = settings.wslDistribution
        settings.wslDistribution = distribution
        try {
            ToolHosts.reset()
            ToolHosts.refreshNow()

            // 1. The system's packages, Node.js first among them: the SDK's installer runs npm.
            val packages = PebbleEnvironment.firstProblem(PebbleEnvironment.check())
            assertNotNull("nothing in the way on a fresh distribution", packages)
            assertEquals(packages.toString(), Concern.NODE, packages!!.concern)
            assertEquals(Fix.RUN_IN_TERMINAL, packages.fix)
            val command = packages.command!!
            assertTrue(command, command.startsWith("sudo apt update && sudo apt install build-essential nodejs npm libsdl2-2.0-0"))
            asRoot(distribution, command)
            ToolHosts.refreshNow()

            // 2. Set Up, with nothing more from the user.
            val setUp = PebbleEnvironment.firstProblem(PebbleEnvironment.check())
            assertEquals(setUp.toString(), Fix.SET_UP, setUp?.fix)
            PebbleSdkSetup.setUp(EmptyProgressIndicator())
            ToolHosts.refreshNow()

            // 3. Nothing missing, the emulator included.
            val items = PebbleEnvironment.check()
            assertNull(items.joinToString("\n"), PebbleEnvironment.firstRunProblem(items))
            assertEquals(items.joinToString("\n"), Status.READY, PebbleEnvironment.of(items, Concern.NODE)?.status)
            val tool = PebbleSdkService.getInstance().tool()!!

            // 4. A project with PebbleKit JS builds — webpack is Linux's — on a Windows drive and in the distribution.
            val onWindows = LiveSdk.fixture("fixture-c")
            build(tool, onWindows)
            val host = ToolHosts.current() as WslToolHost
            val inside = host.idePath("/home/tester/pebble-onboarding/fixture-c")!!
            copy(Path.of(LiveSdk::class.java.getResource("/fixture-c/package.json")!!.toURI()).parent, inside)
            build(tool, inside)
        } finally {
            settings.wslDistribution = saved
            ToolHosts.reset()
        }
    }

    private fun build(tool: Path, root: Path) {
        val output = CapturingProcessHandler(PebbleCommands.command(tool, listOf("build"), root)).runProcess(600_000)
        assertEquals("pebble build in $root:\n${output.stdout}\n${output.stderr}", 0, output.exitCode)
        assertTrue(Files.list(root.resolve("build")).use { files -> files.anyMatch { it.toString().endsWith(".pbw") } })
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun copy(from: Path, to: Path) {
        Files.createDirectories(to.parent)
        from.copyToRecursively(to, followLinks = false, overwrite = true)
    }

    /** What the user runs in the terminal tab, with sudo's password: as root, and answering apt's question. */
    private fun asRoot(distribution: String, command: String) {
        val script = command.replace("sudo ", "").replace("apt install", "DEBIAN_FRONTEND=noninteractive apt-get install -y")
        val process = ProcessBuilder(listOf(WslDistributions.EXE, "--distribution", distribution, "--user", "root", "--exec", "/bin/sh", "-c", script).map(WslProcesses::quoteForWindows))
            .redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        assertTrue("$command did not finish", process.waitFor(20, TimeUnit.MINUTES))
        assertEquals(text.takeLast(3000), 0, process.exitValue())
    }
}
