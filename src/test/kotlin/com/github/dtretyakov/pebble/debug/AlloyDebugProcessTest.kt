package com.github.dtretyakov.pebble.debug

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerTestUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.frame.XNamedValue
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The debug process in a real debug session, with a stand-in for pebble-tool's bridge replaying
 * what the engine sent in a recorded session — its source path made this test's own.
 */
class AlloyDebugProcessTest : IdeTestCase() {

    private lateinit var main: VirtualFile
    private lateinit var enginePath: String
    private val output = StringBuffer()

    override fun setUp() {
        super.setUp()
        val root = Files.createTempDirectory("alloy-debug").toRealPath().resolve("watchface")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        root.createDirectories().resolve("package.json").writeText("""{"name": "watchface", "pebble": {"projectType": "moddable", "targetPlatforms": ["emery"]}}""")
        val file = root.resolve("src/embeddedjs").createDirectories().resolve("main.js")
        file.writeText(javaClass.getResource("/fixture-alloy/src/embeddedjs/main.js")!!.readText())
        enginePath = file.toString()
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
        main = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
    }

    /** The recorded session, message by message, with its path replaced by this test's file. */
    private val recorded: List<String> by lazy {
        String(javaClass.getResource("/xsbug/session.bin")!!.readBytes(), Charsets.UTF_8)
            .replace("/work/watchface/src/embeddedjs/main.js", enginePath)
            .split("</xsbug>").filter { "<xsbug>" in it }.map { it.substring(it.indexOf("<xsbug>")) + "</xsbug>" }
    }

    fun `test breakpoints go to the app when it logs in, a break shows where it stopped and what is there, and resume goes on`() {
        // Line 16 of the fixture: `const width = render.getTextWidth(msg, font);`
        val breakpoint = XDebuggerTestUtil.toggleBreakpoint(project, main, 15)
        assertNotNull("a gutter click sets a breakpoint here", breakpoint)

        val server = XsbugServer.bind(0, object : XsbugServer.Listener {
            override fun connected(connection: XsbugConnection, login: XsbugLogin) = Unit
            override fun message(connection: XsbugConnection, message: XsbugMessage) = Unit
            override fun disconnected(connection: XsbugConnection) = Unit
        })
        val handler = NopProcessHandler()
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            },
        )
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val session = XDebuggerManager.getInstance(project).startSessionAndShowTab(
            "Alloy",
            null as RunContentDescriptor?,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess = AlloyDebugProcess(session, server, DefaultExecutionResult(console, handler))
            },
        )

        val bridge = Bridge(server.port)
        try {
            bridge.send(recorded.first { "<login" in it })
            val breakpoints = bridge.command { it.startsWith("<set-all-breakpoints>") }
            assertTrue(breakpoints, """<breakpoint path="$enginePath" line="16"/>""" in breakpoints)

            bridge.send("<xsbug><log>Hello, Watchface.</log><log>&#10;</log></xsbug>")
            bridge.send(recorded.first { "<break" in it })
            waitUntil { session.isSuspended }
            assertEquals(main, session.currentPosition!!.file)
            assertEquals(15, session.currentPosition!!.line)
            assertTrue(output.toString(), "Hello, Watchface.\n" in output.toString())

            val frames = XDebuggerTestUtil.collectFrames(session)
            assertEquals(4, frames.size)
            assertEquals(main, frames[0].sourcePosition!!.file)

            val locals = XDebuggerTestUtil.collectChildren(frames[0]).filterIsInstance<XNamedValue>().associateBy { it.name }
            assertTrue(locals.keys.toString(), locals.keys.containsAll(listOf("msg", "render", "(function)")))
            assertTrue(XDebuggerTestUtil.computePresentation(locals.getValue("msg")).myValue.matches(Regex("\\d\\d:\\d\\d:\\d\\d")))

            // Expanding an object asks the engine to, and shows what it answers.
            bridge.replyTo("<toggle id=\"@20028684\"/>", recorded.filter { "<local" in it && "<break" !in it }[2])
            val function = XDebuggerTestUtil.collectChildren(locals.getValue("(function)"))
            assertTrue(function.isNotEmpty())

            session.resume()
            assertEquals("<go/>", bridge.command { it == "<go/>" })
            waitUntil { !session.isSuspended }
        } finally {
            bridge.close()
            session.stop()
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(session.runContentDescriptor)
            Disposer.dispose(console)
        }
    }

    fun `test the gutter offers one kind of breakpoint on an Alloy source`() {
        val offered = XLineBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<XLineBreakpointType<*>>()
            .filter { it.canPutAt(main, 15, project) }
            .map { it.id }
        assertEquals(1, offered.size)
        val expected = if (AlloyBreakpoints.javascriptLineType() != null) "javascript" else AlloyLineBreakpointType.ID
        assertEquals(expected, offered.single())
        assertFalse(AlloyBreakpoints.isAlloySource(project, main.parent.parent.parent.findChild("package.json")!!))
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out")
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
    }

    /** pebble-tool's end: sends what the engine sent, and hears the debugger's commands. */
    private class Bridge(port: Int) : AutoCloseable {
        private val socket = Socket(InetAddress.getLoopbackAddress(), port)
        private val commands = LinkedBlockingQueue<String>()
        private val replies = java.util.concurrent.ConcurrentHashMap<String, String>()

        init {
            Thread {
                val input = socket.getInputStream().bufferedReader(Charsets.UTF_8)
                runCatching {
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isBlank()) continue
                        replies.remove(line)?.let { send(it) }
                        commands += line
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        fun send(message: String) = synchronized(this) {
            socket.getOutputStream().apply {
                write("\r\n$message\r\n".toByteArray(Charsets.UTF_8))
                flush()
            }
        }

        fun replyTo(command: String, message: String) {
            replies[command] = message
        }

        fun command(match: (String) -> Boolean): String {
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val next = commands.poll(100, TimeUnit.MILLISECONDS)
                if (next != null && match(next)) return next
                UIUtil.dispatchAllInvocationEvents()
            }
            throw AssertionError("no such command")
        }

        override fun close() = socket.close()
    }
}
