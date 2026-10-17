package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Fix
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Status
import com.github.dtretyakov.pebble.sdk.EmulatorStart
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.HostPackages
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories

class PebbleEnvironmentTest {

    @TempDir
    lateinit var dir: Path

    private val tool get() = dir.resolve("pebble")
    private val node get() = dir.resolve("node")

    @Test
    fun `nothing installed asks for pebble-tool first, and offers no SDK install without it`() {
        val items = PebbleEnvironment.check(HostOs.LINUX, null, "", PebbleSdks.NONE, node)
        val first = PebbleEnvironment.firstProblem(items)!!
        assertEquals(Concern.TOOL, first.concern)
        assertEquals(Fix.SET_UP, first.fix)
        val sdk = items.single { it.concern == Concern.SDK }
        assertNull(sdk.fix)
        assertTrue("pebble-tool" in sdk.detail, "The SDK line should say what comes first: ${sdk.detail}")
    }

    @Test
    fun `details are shown as HTML with code set as code, and finished as sentences`() {
        assertEquals("Run <code>pebble sdk install 4.33</code> &lt;now&gt;.", PebbleEnvironment.html("Run `pebble sdk install 4.33` <now>."))

        val ready = PebbleEnvironment.Item(Concern.NODE, "Node.js", Status.READY, "Found at /usr/bin/node")
        assertEquals("Found at /usr/bin/node.", PebbleEnvironment.sentence(ready))
        assertEquals("Not found.", PebbleEnvironment.sentence(ready.copy(detail = "Not found.")))
    }

    @Test
    fun `pebble-tool without an SDK offers to install one`() {
        val items = PebbleEnvironment.check(HostOs.LINUX, tool, "", PebbleSdks.NONE, node)
        val first = PebbleEnvironment.firstProblem(items)!!
        assertEquals(Concern.SDK, first.concern)
        assertEquals(Fix.SET_UP, first.fix)
    }

    @Test
    fun `SDKs installed but none active offers to activate one`() {
        val sdks = PebbleSdks(listOf(sdk("4.33.1")), current = null)
        val first = PebbleEnvironment.firstProblem(PebbleEnvironment.check(HostOs.MAC, tool, "", sdks, node))!!
        assertEquals(Fix.ACTIVATE_SDK, first.fix)
    }

    @Test
    fun `an active SDK without its toolchain is a problem`() {
        val sdk = sdk("4.33.1")
        val first = PebbleEnvironment.firstProblem(
            PebbleEnvironment.check(HostOs.LINUX, tool, "", PebbleSdks(listOf(sdk), sdk), node),
        )!!
        assertEquals(Concern.TOOLCHAIN, first.concern)
    }

    @Test
    fun `a complete environment has no problem, and a missing Node is only worth knowing`() {
        val sdk = sdk("4.33.1")
        sdk.compilerBin.createDirectories()
        val items = PebbleEnvironment.check(HostOs.LINUX, tool, "", PebbleSdks(listOf(sdk), sdk), node = null)
        assertNull(PebbleEnvironment.firstProblem(items))
        assertEquals(Status.MISSING, items.single { it.concern == Concern.NODE }.status)
    }

    @Test
    fun `C code insight is only worth knowing, and absent where the IDE has its own`() {
        val sdk = sdk("4.33.1")
        sdk.compilerBin.createDirectories()
        val sdks = PebbleSdks(listOf(sdk), sdk)

        val clion = PebbleEnvironment.check(HostOs.LINUX, tool, "", sdks, node, PebbleEnvironment.CInsight.NATIVE)
        assertNull(clion.firstOrNull { it.concern == Concern.C_INSIGHT })

        val idea = PebbleEnvironment.check(HostOs.LINUX, tool, "", sdks, node, PebbleEnvironment.CInsight.NO_LSP4IJ)
        assertNull(PebbleEnvironment.firstProblem(idea))
        assertEquals(Fix.INSTALL_LSP4IJ, idea.single { it.concern == Concern.C_INSIGHT }.fix)

        val noClangd = PebbleEnvironment.check(HostOs.LINUX, tool, "", sdks, node, PebbleEnvironment.CInsight.NO_CLANGD)
        assertEquals(Fix.HOW_TO_INSTALL_CLANGD, noClangd.single { it.concern == Concern.C_INSIGHT }.fix)

        val withBrew = PebbleEnvironment.check(
            HostOs.MAC, tool, "", sdks, node, PebbleEnvironment.CInsight.NO_CLANGD,
            HostPackages(HostOs.MAC, Path.of("/opt/homebrew/bin/brew"), null),
        ).single { it.concern == Concern.C_INSIGHT }
        assertEquals(Fix.RUN_IN_TERMINAL, withBrew.fix)
        assertEquals("/opt/homebrew/bin/brew install llvm", withBrew.command)
    }

    @Test
    fun `a configured pebble-tool that is not there is not offered Set Up`() {
        val first = PebbleEnvironment.firstProblem(PebbleEnvironment.check(HostOs.MAC, null, "/nowhere/pebble", PebbleSdks.NONE, node))!!
        assertEquals(Concern.TOOL, first.concern)
        assertNull(first.fix)
    }

    @Test
    fun `the emulator is reported once it has been asked, with the command that fixes it`() {
        val sdk = sdk("4.33.1")
        sdk.compilerBin.createDirectories()
        val sdks = PebbleSdks(listOf(sdk), sdk)
        val ubuntu = HostPackages(HostOs.LINUX, null, HostPackages.Distro.DEBIAN)

        assertNull(PebbleEnvironment.check(HostOs.LINUX, tool, "", sdks, node, packages = ubuntu).firstOrNull { it.concern == Concern.EMULATOR })

        val missing = PebbleEnvironment.check(
            HostOs.LINUX, tool, "", sdks, node, packages = ubuntu,
            emulator = EmulatorStart.Verdict.MissingLibrary("libSDL2-2.0.so.0", null),
        )
        val emulator = missing.single { it.concern == Concern.EMULATOR }
        assertEquals(Fix.RUN_IN_TERMINAL, emulator.fix)
        assertTrue(emulator.command!!.startsWith("sudo apt install libsdl2-2.0-0"))
        // Building does not need it; running does.
        assertNull(PebbleEnvironment.firstProblem(missing))
        assertEquals(Concern.EMULATOR, PebbleEnvironment.firstRunProblem(missing)?.concern)

        val old = PebbleEnvironment.check(
            HostOs.LINUX, tool, "", sdks, node, packages = ubuntu, emulator = EmulatorStart.Verdict.OldGlibc("2.38"),
        ).single { it.concern == Concern.EMULATOR }
        assertNull(old.fix)
        assertTrue("2.38" in old.detail)
    }

    @Test
    fun `an Intel Mac is not offered a libpng Homebrew would put where the emulator does not look`() {
        val sdk = sdk("4.33.1")
        sdk.compilerBin.createDirectories()
        val sdks = PebbleSdks(listOf(sdk), sdk)
        val libpng = EmulatorStart.Verdict.MissingLibrary("libpng16.16.dylib", "libpng", "/opt/homebrew/opt/libpng/lib/libpng16.16.dylib")

        val intel = HostPackages(HostOs.MAC, Path.of("/usr/local/bin/brew"), null, appleSilicon = false)
        val there = PebbleEnvironment.check(HostOs.MAC, tool, "", sdks, node, packages = intel, emulator = libpng)
            .single { it.concern == Concern.EMULATOR }
        assertNull(there.fix)
        assertTrue("Apple silicon" in there.detail, there.detail)

        val silicon = HostPackages(HostOs.MAC, Path.of("/opt/homebrew/bin/brew"), null)
        val here = PebbleEnvironment.check(HostOs.MAC, tool, "", sdks, node, packages = silicon, emulator = libpng)
            .single { it.concern == Concern.EMULATOR }
        assertEquals("/opt/homebrew/bin/brew install libpng", here.command)
    }

    @Test
    fun `Windows is one item, not a list of its consequences`() {
        val items = PebbleEnvironment.check(HostOs.WINDOWS, null, "", PebbleSdks.NONE, null)
        assertEquals(listOf(Concern.HOST), items.map { it.concern })
    }

    private fun sdk(version: String) = PebbleSdk(dir.resolve("SDKs").resolve(version), version, "release")
}
