package com.github.dtretyakov.pebble.project

import com.github.dtretyakov.pebble.project.PebbleEnvironment.Concern
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Fix
import com.github.dtretyakov.pebble.project.PebbleEnvironment.Status
import com.github.dtretyakov.pebble.sdk.HostOs
import com.github.dtretyakov.pebble.sdk.PebbleSdk
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
        assertEquals(Fix.INSTALL_TOOL, first.fix)
        assertNull(items.single { it.concern == Concern.SDK }.fix)
    }

    @Test
    fun `pebble-tool without an SDK offers to install one`() {
        val items = PebbleEnvironment.check(HostOs.LINUX, tool, "", PebbleSdks.NONE, node)
        val first = PebbleEnvironment.firstProblem(items)!!
        assertEquals(Concern.SDK, first.concern)
        assertEquals(Fix.INSTALL_SDK, first.fix)
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
    fun `Windows is one item, not a list of its consequences`() {
        val items = PebbleEnvironment.check(HostOs.WINDOWS, null, "", PebbleSdks.NONE, null)
        assertEquals(listOf(Concern.HOST), items.map { it.concern })
    }

    private fun sdk(version: String) = PebbleSdk(dir.resolve("SDKs").resolve(version), version, "release")
}
