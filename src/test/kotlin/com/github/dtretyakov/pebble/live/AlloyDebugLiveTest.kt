package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.debug.AlloySources
import com.github.dtretyakov.pebble.debug.XsbugCommands
import com.github.dtretyakov.pebble.debug.XsbugCommands.Breakpoint
import com.github.dtretyakov.pebble.debug.XsbugConnection
import com.github.dtretyakov.pebble.debug.XsbugLogin
import com.github.dtretyakov.pebble.debug.XsbugMessage
import com.github.dtretyakov.pebble.debug.XsbugServer
import com.github.dtretyakov.pebble.run.PebbleDebugRunner
import com.github.dtretyakov.pebble.debug.XscCompiler
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.run.PebbleLogProcessHandler
import com.github.dtretyakov.pebble.run.PebbleRunState
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.openapi.util.Key
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * Debug for Alloy as the run does it, against pebble-tool and the gabbro emulator: the plugin's
 * server where xsbug would listen, a debug build installed with logs, and the app's JavaScript
 * engine logging in to the IDE. Every fact the debugger relies on is checked here, so the day
 * pebble-tool or the engine changes one, this turns red.
 *
 * On gabbro, apart from the other live tests' emery: straight after this one, in the same test
 * run, emery stopped taking touches — buttons still worked — although the ordinary build was
 * back on it, and run on its own the touch test passed. Not understood yet; kept apart.
 */
class AlloyDebugLiveTest : IdeTestCase() {

    fun `test the IDE is the app's debugger, and the app runs on after it logs out`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-alloy")
        assertEquals(0, LiveSdk.run(root, "build", "--debug").exitCode)
        // gabbro up first, with an ordinary app, past pebble-tool's few seconds' wait for a cold one.
        val warm = LiveSdk.fixture("fixture-c")
        assertEquals(0, LiveSdk.run(warm, "build").exitCode)
        LiveSdk.install(warm, "--emulator", "gabbro", "--vnc")

        // Line 16 of the fixture's draw(), which a watchface runs every second.
        val main = AlloySources.engineName(root.resolve("src/embeddedjs/main.js"), LiveSdk.host::toolPath)
        val line16 = Breakpoint(main, 16)
        val logins = LinkedBlockingQueue<XsbugConnection>()
        val breaks = LinkedBlockingQueue<XsbugMessage>()
        val logs = StringBuffer()
        val server = PebbleDebugRunner.xsbugServer(
            listener = object : XsbugServer.Listener {
                override fun connected(connection: XsbugConnection, login: XsbugLogin) {
                    connection.send(XsbugCommands.setAllBreakpoints(listOf(line16, XsbugCommands.EXCEPTIONS)))
                    logins += connection
                }

                override fun message(connection: XsbugConnection, message: XsbugMessage) {
                    message.logs.forEach { logs.append(it.text) }
                    if (message.breakAt != null) breaks += message
                }

                override fun disconnected(connection: XsbugConnection) = Unit
            },
        )
        val output = StringBuffer()
        var loggedOut = false
        try {
            val handler = PebbleRunState.processHandler(
                tool, PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "gabbro", "", "", logs = true), root,
            ) as PebbleLogProcessHandler
            handler.addProcessListener(
                object : ProcessListener {
                    override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                        output.append(event.text)
                    }
                },
            )
            handler.startNotify()
            try {
                val connection = logins.poll(4, TimeUnit.MINUTES) ?: return fail("the app never logged in:\n$output")
                assertFalse(output.toString(), "Launching xsbug" in output)

                val first = breaks.poll(30, TimeUnit.SECONDS) ?: return fail("no break:\n$output")
                assertEquals(main, first.breakAt!!.path)
                assertEquals(16, first.breakAt!!.line)
                val frames = first.frames!!
                assertEquals("draw", frames[0].name)
                val msg = first.local!!.properties.first { it.name == "msg" }
                assertTrue(msg.toString(), msg.value!!.matches(Regex("'\\d\\d:\\d\\d:\\d\\d'")))
                assertTrue(logs.toString(), "Hello, Watchface." in logs)

                // Expressions, compiled here by the SDK's xsc and evaluated there.
                val compiler = XscCompiler.forSdk(LiveSdk.sdks().current!!, LiveSdk.host)!!
                fun code(expression: String) = (compiler.compile(expression) as XscCompiler.Result.Compiled).hex
                val length = connection.request(XsbugCommands.eval(frames[0].id, code("msg.length"), 0)).get(10, TimeUnit.SECONDS).result!!
                assertEquals("8", length.value!!.value)
                val thrown = connection.request(XsbugCommands.eval(frames[0].id, code("nope.x"), 1)).get(10, TimeUnit.SECONDS).result!!
                assertTrue(thrown.toString(), thrown.error!!.contains("ReferenceError"))
                assertTrue(compiler.compile("msg.(") is XscCompiler.Result.Failed)

                val caller = connection.request(XsbugCommands.select(frames[1].id)).get(10, TimeUnit.SECONDS)
                assertEquals(frames[1].name, caller.local!!.frame.name)

                connection.send(XsbugCommands.step())
                val stepped = breaks.poll(10, TimeUnit.SECONDS) ?: return fail("no step")
                assertTrue(stepped.breakAt.toString(), stepped.breakAt!!.line!! > 16)

                connection.send(XsbugCommands.go())
                val again = breaks.poll(10, TimeUnit.SECONDS) ?: return fail("draw() did not stop again")
                assertEquals(16, again.breakAt!!.line)

                // A condition that never holds does not stop; a hit count stops on the third pass;
                // a trace logs and goes on.
                connection.send(XsbugCommands.clearBreakpoint(line16))
                connection.send(XsbugCommands.setBreakpoint(line16.copy(condition = code("msg.length > 100"))))
                connection.send(XsbugCommands.go())
                assertNull("stopped although the condition never holds", breaks.poll(3, TimeUnit.SECONDS))
                connection.send(XsbugCommands.clearBreakpoint(line16))
                connection.send(XsbugCommands.setBreakpoint(line16.copy(hitCount = ">=3")))
                assertNotNull("the hit count never stopped", breaks.poll(10, TimeUnit.SECONDS))
                connection.send(XsbugCommands.clearBreakpoint(line16))
                connection.send(XsbugCommands.setBreakpoint(line16.copy(trace = code("'trace ' + msg"))))
                connection.send(XsbugCommands.go())
                assertNull("a trace stopped", breaks.poll(3, TimeUnit.SECONDS))
                assertTrue(logs.toString(), Regex("trace \\d\\d:\\d\\d:\\d\\d").containsMatchIn(logs))

                connection.send(XsbugCommands.clearBreakpoint(line16))
                assertNull("stopped after the breakpoint was cleared", breaks.poll(3, TimeUnit.SECONDS))

                // Pausing a running app stops it at its next statement.
                connection.send(XsbugCommands.pause())
                val paused = breaks.poll(10, TimeUnit.SECONDS) ?: return fail("pause did not stop")
                assertEquals(main, paused.breakAt!!.path)
                connection.send(XsbugCommands.go())

                // Stopped the way Stop stops a debug session: log out first, then pebble-tool goes.
                handler.beforeDestroy = { loggedOut = connection.logoutAndAwaitClose(5000) }
            } finally {
                handler.destroyProcess()
                handler.waitFor(20_000)
            }
        } finally {
            server.close()
        }
        assertTrue("the bridge did not hang up after logout:\n$output", loggedOut)

        try {
            // The app runs on by itself: its clock moves between two screenshots.
            val before = screenshot(root, "before")
            Thread.sleep(1500)
            val after = screenshot(root, "after")
            assertFalse("the watchface stopped drawing", sameImage(before, after))
        } finally {
            // The ordinary build back, as Run would put it: a debug build left on the watch waits
            // for a debugger the next time it starts, and its "not responding" dialog would take
            // the next tests' buttons and touches.
            assertEquals(0, LiveSdk.run(root, "build").exitCode)
            LiveSdk.install(root, "--emulator", "gabbro", "--vnc")
        }
    }

    private fun screenshot(root: Path, name: String): Path {
        val file = root.resolve("$name.png")
        val result = LiveSdk.run(root, "screenshot", "--emulator", "gabbro", "--vnc", "--no-open", LiveSdk.host.toolPath(file) ?: file.toString())
        assertEquals(result.text, 0, result.exitCode)
        assertTrue(Files.exists(file))
        return file
    }

    private fun sameImage(a: Path, b: Path): Boolean {
        val first = ImageIO.read(a.toFile())
        val second = ImageIO.read(b.toFile())
        if (first.width != second.width || first.height != second.height) return false
        for (y in 0 until first.height) for (x in 0 until first.width) if (first.getRGB(x, y) != second.getRGB(x, y)) return false
        return true
    }
}
