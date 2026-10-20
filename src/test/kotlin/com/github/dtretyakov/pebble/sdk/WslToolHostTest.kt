package com.github.dtretyakov.pebble.sdk

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path

class WslToolHostTest {

    /** As Ubuntu 24.04 with pebble-tool installed by uv answers [WslToolHost.PROBE_SCRIPT]. */
    private val answer = """
        home=/home/me
        data=/home/me/.local/share/pebble-sdk
        temp=/tmp
        arch=x86_64
        mount=/mnt/
        current=/home/me/.local/share/pebble-sdk/SDKs/4.33.1
        pebble=/home/me/.local/bin/pebble
        uv=/home/me/.local/bin/uv
        node=
        clangd=/usr/bin/clangd-18
        networking=nat
        env.PATH=/home/me/.local/bin:/usr/bin:/mnt/c/Windows
        env.XDG_DATA_HOME=
        env.XDG_BIN_HOME=
        env.UV_TOOL_BIN_DIR=
        os.PRETTY_NAME="Ubuntu 24.04.3 LTS"
        os.ID=ubuntu
        os.ID_LIKE=debian
        os.VERSION_ID="24.04"
    """.trimIndent()

    private class Recorder(private val output: WslToolHost.Output?) : WslToolHost.Runner {
        val calls = mutableListOf<List<String>>()
        override fun run(words: List<String>, timeoutMillis: Long): WslToolHost.Output? {
            calls += words
            return output
        }
    }

    private fun host(output: WslToolHost.Output? = WslToolHost.Output(0, answer, "")) =
        WslToolHost("Ubuntu", wslExe = "wsl.exe", runner = Recorder(output))

    @Test
    fun `what the distribution says is read`() {
        val probe = WslProbe.parse(answer)!!
        assertEquals("/home/me", probe.home)
        assertEquals("/home/me/.local/share/pebble-sdk/SDKs/4.33.1", probe.currentSdk)
        assertEquals(mapOf(HostTool.PEBBLE to "/home/me/.local/bin/pebble", HostTool.UV to "/home/me/.local/bin/uv", HostTool.CLANGD to "/usr/bin/clangd-18"), probe.tools)
        assertEquals("nat", probe.networking)
        assertEquals(HostPackages.Distro.DEBIAN, HostPackages.distro(probe.osRelease))
        assertNull(WslProbe.parse("wsl: something went wrong"))
    }

    @Test
    fun `tools are where the distribution found them, as the IDE reaches them`() {
        val host = host()
        assertNull(host.locate(HostTool.PEBBLE))
        assertTrue(host.refresh())
        assertEquals(Path.of("""\\wsl.localhost\Ubuntu\home\me\.local\bin\pebble"""), host.locate(HostTool.PEBBLE))
        assertNull(host.locate(HostTool.NODE))
        assertEquals("x86_64", host.arch)
        assertEquals(Path.of("""\\wsl.localhost\Ubuntu\tmp"""), host.tempDirectory)
        // The same answer twice is no change.
        assertFalse(host.refresh())
    }

    @Test
    fun `the active SDK is the link the distribution followed, the share showing none`() {
        val host = host().also { it.refresh() }
        assertEquals(
            Path.of("""\\wsl.localhost\Ubuntu\home\me\.local\share\pebble-sdk\SDKs\4.33.1"""),
            host.resolveLink(Path.of("""\\wsl.localhost\Ubuntu\home\me\.local\share\pebble-sdk\SDKs\current""")),
        )
    }

    @Test
    fun `the data directory follows the Linux rule, read through the share`() {
        val host = host().also { it.refresh() }
        // As text with Windows' separators: off Windows, a share's path is a file name with backslashes in it.
        assertEquals("""\\wsl.localhost\Ubuntu\home\me\.local\share\pebble-sdk""", PebbleDataDir.locate(host).toString().replace('/', '\\'))
    }

    @Test
    fun `a tool runs through wsl, in the project's directory, in a login shell, with its environment exported`() {
        val host = host().also { it.refresh() }
        val command = host.command(
            Path.of("""\\wsl.localhost\Ubuntu\home\me\.local\bin\pebble"""),
            listOf("build", "--debug"),
            Path.of("""C:\Users\me\My App"""),
            mapOf("PYTHONWARNINGS" to "ignore"),
        )
        assertEquals(
            listOf("wsl.exe", "--distribution", "Ubuntu", "--cd", "/mnt/c/Users/me/My App", "--exec", "/bin/sh", "-lc"),
            command.words.take(8),
        )
        val script = command.words[8]
        // Windows' own directories off the PATH: its npm is no tool for a Linux SDK.
        assertTrue(script.startsWith("PATH=\$(printf %s \"\$PATH\" | tr : '\\n' | grep -v '^/mnt/[a-z]/' | paste -sd: -); export PATH; "), script)
        assertTrue(script.endsWith("export PYTHONWARNINGS=ignore; exec \"\$0\" \"\$@\""), script)
        assertEquals(listOf("/home/me/.local/bin/pebble", "build", "--debug"), command.words.drop(9))
        assertNull(command.workDirectory)
        assertEquals("1", command.environment["WSL_UTF8"])
    }

    @Test
    fun `a directory the distribution cannot reach is refused, not run somewhere else`() {
        val host = host()
        val error = assertThrows(IllegalArgumentException::class.java) {
            host.command(Path.of("""\\wsl.localhost\Ubuntu\usr\bin\true"""), emptyList(), Path.of("""\\wsl.localhost\Debian\home\me\app"""))
        }
        assertTrue("Debian" in error.message!!, error.message)
    }

    @Test
    fun `a distribution that does not answer is a problem, not an empty machine`() {
        val silent = host(output = null)
        assertTrue(silent.refresh(timeoutMillis = 1_000))
        assertNull(silent.probe)
        assertNotNull(silent.problem)

        val failing = host(output = WslToolHost.Output(1, "", "There is no distribution with the supplied name.\n"))
        failing.refresh()
        assertEquals("There is no distribution with the supplied name.", failing.problem)
    }

    @Test
    fun `configured paths are asked about in the distribution's spelling`() {
        val runner = Recorder(WslToolHost.Output(0, answer, ""))
        val host = WslToolHost("Ubuntu", wslExe = "wsl.exe", runner = runner)
        host.refresh("""\\wsl.localhost\Ubuntu\opt\pebble""", "/usr/bin/clangd")
        assertEquals(listOf("/opt/pebble", "/usr/bin/clangd"), runner.calls.single().takeLast(2))
    }

    @Test
    fun `values are quoted for the shell only when they need it`() {
        assertEquals("ignore", WslToolHost.shellQuote("ignore"))
        assertEquals("'a b'", WslToolHost.shellQuote("a b"))
        assertEquals("'it'\\''s'", WslToolHost.shellQuote("it's"))
    }

    @Test
    fun `wsl lists its distributions, the default marked, Docker's left out`() {
        val listed = WslDistributions.parse(
            """
              NAME              STATE           VERSION
            * Ubuntu-24.04      Running         2
              Debian            Stopped         1
              docker-desktop    Stopped         2
            """.trimIndent(),
        )
        assertEquals(
            listOf(WslDistributions.Distribution("Ubuntu-24.04", true, 2), WslDistributions.Distribution("Debian", false, 1)),
            listed,
        )
    }

    @Test
    fun `an argument reaches the process whole, quotes and all`() {
        if (HostOs.current() != HostOs.WINDOWS) return
        assertEquals("plain", WslProcesses.quoteForWindows("plain"))
        assertEquals("\"a b\"", WslProcesses.quoteForWindows("a b"))
        assertEquals("\"say \\\"hi\\\"\"", WslProcesses.quoteForWindows("say \"hi\""))
        // Backslashes before the closing quote are doubled, so it stays a closing quote.
        assertEquals("\"a b\\\\\"", WslProcesses.quoteForWindows("a b\\"))
        assertEquals("\"\"", WslProcesses.quoteForWindows(""))
    }
}
