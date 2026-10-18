package com.github.dtretyakov.pebble.debug.gdb

import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
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
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.frame.XNamedValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The debug process in a real debug session, against a stand-in for gdb that answers with what
 * the SDK's gdb answered in a recorded session — its paths made this test's own.
 */
class GdbDebugProcessTest : IdeTestCase() {

    private lateinit var main: VirtualFile
    private lateinit var path: String
    private val output = StringBuffer()

    override fun setUp() {
        super.setUp()
        val root = Files.createTempDirectory("gdb-debug").toRealPath().resolve("app")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        root.createDirectories().resolve("package.json").writeText("""{"name": "app", "pebble": {"projectType": "native", "targetPlatforms": ["emery"]}}""")
        val file = root.resolve("src/c").createDirectories().resolve("fixture-c.c")
        file.writeText(
            """
            #include <pebble.h>
            static Window *s_window;
            static void select_clicked(ClickRecognizerRef recognizer, void *context) {
              APP_LOG(APP_LOG_LEVEL_INFO, "select");
            }
            static void click_config(void *context) {
              window_single_click_subscribe(BUTTON_ID_SELECT, select_clicked);
            }
            int main(void) {
              s_window = window_create();
              window_set_click_config_provider(s_window, click_config);
              window_stack_push(s_window, false);
              app_event_loop();
            }
            """.trimIndent(),
        )
        path = file.toString()
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
        main = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file)!!
    }

    /** The recorded session's line answering command [token], with this test's path. */
    private fun recorded(token: Int): String = recorded { it.startsWith("$token^") }.replaceFirst("$token^", "{t}^")

    private fun recorded(match: (String) -> Boolean): String =
        javaClass.getResource("/gdb/session.mi")!!.readText().lines().first(match).replace("/work/app/src/c/fixture-c.c", path)

    private val stoppedAtBreakpoint by lazy { recorded { it.startsWith("*stopped,reason=\"breakpoint-hit\"") } }

    private fun gdb(): FakeGdb = FakeGdb().apply {
        answer("-target-select remote :1234", recorded { it.startsWith("*stopped,frame=") }, "{t}^connected")
        answer("-break-insert app_crashed", recorded(4))
        answer("-break-insert --source .* --line 4", recorded(7).replace("number=\"4\"", "number=\"2\""))
        answer("-break-insert .*--line 5", recorded(7).replace("number=\"4\"", "number=\"3\"").replace("line=\"4\"", "line=\"5\""))
        answer("-exec-continue", "{t}^running", "*running,thread-id=\"all\"")
        answer("-exec-next", "{t}^running", "*running,thread-id=\"all\"")
        answer("-exec-finish.*", "{t}^running", "*running,thread-id=\"all\"")
        answer(
            "-exec-interrupt",
            "{t}^done",
            recorded { it.startsWith("*stopped,reason=\"signal-received\"") },
        )
        answer("-stack-list-frames --thread 1 0 99", recorded(9))
        answer("-stack-list-variables --thread 1 --frame 0 --no-values", "{t}^done,variables=[{name=\"recognizer\",arg=\"1\"},{name=\"context\",arg=\"1\"}]")
        answer("-var-create --thread 1 --frame 0 - \\* \"recognizer\"", "{t}^done,name=\"var1\",numchild=\"0\",value=\"0x20070534\",type=\"ClickRecognizerRef\",thread-id=\"1\",has_more=\"0\"")
        answer("-var-create --thread 1 --frame 0 - \\* \"context\"", recorded(12).replace("var1", "var2"))
        answer(
            "-var-create --thread 1 --frame 0 - \\* \"s_clicks\"",
            "{t}^done,name=\"var3\",numchild=\"2\",value=\"{...}\",type=\"Clicks\",has_more=\"0\"",
        )
        answer(
            "-var-list-children --all-values \"var3\" 0 100",
            "{t}^done,numchild=\"2\",children=[child={name=\"var3.count\",exp=\"count\",numchild=\"0\",value=\"3\",type=\"int\"}," +
                "child={name=\"var3.where\",exp=\"where\",numchild=\"2\",value=\"{...}\",type=\"GPoint\"}],has_more=\"0\"",
        )
        answer("-var-create --thread 1 --frame 0 - \\* \"nope\"", "{t}^error,msg=\"-var-create: unable to create variable object\"")
        answer("-target-detach", "=thread-exited,id=\"1\",group-id=\"i1\"", "{t}^done")
        answer("-gdb-exit", "{t}^exit")
    }

    fun `test gdb attaches once the app is installed, stops at a breakpoint, shows the stack and variables, and steps`() {
        // Line 4: `APP_LOG(APP_LOG_LEVEL_INFO, "select");`
        assertNotNull("a gutter click sets a breakpoint here", XDebuggerTestUtil.toggleBreakpoint(project, main, 3))
        val gdb = gdb()
        val setup = GdbSetup(Path.of("arm-none-eabi-gdb"), Path.of("emery_sdk_debug.elf"), 1234, listOf("add-symbol-file \"app.elf\" x+0xa8"))
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
            "C",
            null as RunContentDescriptor?,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess =
                    GdbDebugProcess(session, DefaultExecutionResult(console, handler), { setup }) { _, listener ->
                        GdbConnection(gdb.input, gdb.output, listener).start()
                    }
            },
        )
        val pump = { UIUtil.dispatchAllInvocationEvents() }
        try {
            // Nothing until pebble-tool says the app is installed; then gdb attaches as pebble gdb does.
            handler.notifyTextAvailable("Installing app...\n", ProcessOutputTypes.STDOUT)
            Thread.sleep(200)
            assertTrue(gdb.heard().toString(), gdb.heard().isEmpty())
            handler.notifyTextAvailable("App install succeeded.\n", ProcessOutputTypes.STDOUT)
            assertEquals("-gdb-set confirm off", gdb.next(".*", pump))
            assertEquals("-gdb-set mi-async on", gdb.next(".*", pump))
            gdb.next("-target-select remote :1234", pump)
            assertEquals("-interpreter-exec console \"add-symbol-file \\\"app.elf\\\" x+0xa8\"", gdb.next("-interpreter-exec.*", pump))
            gdb.next("-break-insert app_crashed", pump)
            assertEquals("-break-insert --source \"$path\" --line 4", gdb.next("-break-insert --source.*", pump))
            gdb.next("-exec-continue", pump)
            waitUntil { "gdb is attached" in output }
            assertFalse("the stop on attaching is gdb's, not the user's", session.isSuspended)

            // A breakpoint set while the app runs: interrupted for it, and let go again, unseen. Given
            // a condition, it is set again with it.
            val conditional = XDebuggerTestUtil.toggleBreakpoint(project, main, 4)!!
            conditional.conditionExpression = XDebuggerUtil.getInstance().createExpression("context != 0", null, null, EvaluationMode.EXPRESSION)
            gdb.next("-exec-interrupt", pump)
            assertEquals("-break-insert -c \"context != 0\" --source \"$path\" --line 5", gdb.next("-break-insert -c.*", pump))
            gdb.next("-exec-continue", pump)
            assertFalse(session.isSuspended)

            gdb.emit(stoppedAtBreakpoint)
            waitUntil { session.isSuspended }
            assertEquals(main, session.currentPosition!!.file)
            assertEquals(3, session.currentPosition!!.line)

            val frames = XDebuggerTestUtil.collectFrames(session)
            assertEquals(8, frames.size)
            assertEquals(main, frames[0].sourcePosition!!.file)
            assertNull("the firmware's sources are not here", frames[1].sourcePosition)
            assertEquals(main, frames[5].sourcePosition!!.file)

            val locals = XDebuggerTestUtil.collectChildren(frames[0]).filterIsInstance<XNamedValue>().associateBy { it.name }
            assertEquals(setOf("recognizer", "context"), locals.keys)
            assertEquals("0x20070534", XDebuggerTestUtil.computePresentation(locals.getValue("recognizer")).myValue)

            val evaluated = XDebuggerTestUtil.evaluate(session, "s_clicks")
            assertNull(evaluated.second, evaluated.second)
            val fields = XDebuggerTestUtil.collectChildren(evaluated.first).filterIsInstance<XNamedValue>()
            assertEquals(listOf("count", "where"), fields.map { it.name })
            assertEquals("3", XDebuggerTestUtil.computePresentation(fields[0]).myValue)
            assertEquals("-var-create: unable to create variable object", XDebuggerTestUtil.evaluate(session, "nope").second)

            // What an agent reads of the same stop, through the platform's debugger API.
            val debugger = com.github.dtretyakov.pebble.agent.PebbleDebugger.getInstance(project)
            val state = agentCall { debugger.state() }
            assertEquals("suspended", state.state)
            assertEquals(com.github.dtretyakov.pebble.agent.DebugPosition("src/c/fixture-c.c", 4), state.position)
            assertEquals(8, state.frames.size)
            assertTrue(state.frames[0].text, state.frames[0].text.startsWith("select_clicked"))
            assertNull(state.frames[1].file)
            assertEquals(listOf("recognizer", "context"), state.variables.map { it.name })
            val clicks = agentCall { debugger.evaluate("s_clicks", 0) }
            assertEquals(listOf("count", "where"), clicks.children!!.map { it.name })
            assertEquals("3", clicks.children!![0].value)
            assertEquals(com.github.dtretyakov.pebble.debug.BreakpointStates.State(true, null), com.github.dtretyakov.pebble.debug.BreakpointStates.getInstance(project).of(conditional))

            // Stepping over: the variable objects go, gdb steps, and the session stops on the next line.
            session.stepOver(false)
            assertEquals("-var-delete \"var1\"", gdb.next("-var-delete.*", pump))
            gdb.next("-exec-next", pump)
            gdb.answer("-stack-list-frames --thread 1 0 99", recorded(9).replaceFirst("line=\"4\"", "line=\"5\""))
            gdb.emit(recorded { it.startsWith("*stopped,reason=\"end-stepping-range\"") })
            waitUntil { session.isSuspended && session.currentPosition?.line == 4 }

            // Off the end of the handler into the firmware: on to the app's next frame, main's event loop.
            session.stepOver(false)
            gdb.next("-exec-next", pump)
            gdb.answer("-stack-list-frames --thread 1 0 99", recorded(9).replaceFirst(Regex("""frame=\{level="0".*?\},(frame=\{level="1")"""), "$1"))
            gdb.emit(recorded { it.startsWith("*stopped,reason=\"end-stepping-range\"") }.replace("func=\"select_clicked\"", "func=\"prv_dispatch_event\"").replace(path, "./build/../src/fw/applib/ui/click.c"))
            assertEquals("-exec-finish --thread 1 --frame 4", gdb.next("-exec-finish.*", pump))
            Thread.sleep(200)
            pump()
            assertFalse(session.isSuspended)

            // A crash stops at app_crashed, and says so.
            gdb.answer("-stack-list-frames --thread 1 0 99", recorded(9))
            gdb.emit(stoppedAtBreakpoint.replace("bkptno=\"2\"", "bkptno=\"1\""))
            waitUntil { session.isSuspended }
            assertTrue(output.toString(), "The app crashed" in output)
            session.resume()
            gdb.next("-exec-continue", pump)
        } finally {
            session.stop()
            gdb.next("-target-detach", pump)
            gdb.next("-gdb-exit", pump)
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(session.runContentDescriptor)
            Disposer.dispose(console)
        }
        assertTrue("detached while running: interrupted first", gdb.heard().takeLast(3).first() == "-exec-interrupt")
    }

    fun `test with a breakpoint set, the app is started again once gdb is attached, so its start is debugged`() {
        assertNotNull(XDebuggerTestUtil.toggleBreakpoint(project, main, 3))
        assertEquals(1, attachAndCountRelaunches())
        assertTrue(output.toString(), "Starting the app again" in output)
    }

    fun `test without breakpoints the app is left to run as it was started`() {
        assertEquals(0, attachAndCountRelaunches())
    }

    /** Attaches as the run would, and counts how often the app is started again. */
    private fun attachAndCountRelaunches(): Int {
        val relaunches = java.util.concurrent.atomic.AtomicInteger()
        val gdb = gdb()
        val setup = GdbSetup(Path.of("arm-none-eabi-gdb"), Path.of("emery_sdk_debug.elf"), 1234, listOf("add-symbol-file \"app.elf\" x+0xa8"))
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
            "C",
            null as RunContentDescriptor?,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess =
                    GdbDebugProcess(
                        session, DefaultExecutionResult(console, handler), { setup },
                        relaunch = { relaunches.incrementAndGet(); null },
                    ) { _, listener -> GdbConnection(gdb.input, gdb.output, listener).start() }
            },
        )
        val pump = { UIUtil.dispatchAllInvocationEvents() }
        try {
            handler.notifyTextAvailable("App install succeeded.\n", ProcessOutputTypes.STDOUT)
            gdb.next("-exec-continue", pump)
            waitUntil { "gdb is attached" in output }
            // The relaunch is off the command queue: give it the time it would take to start.
            Thread.sleep(300)
            pump()
        } finally {
            session.stop()
            gdb.next("-target-detach", pump)
            gdb.next("-gdb-exit", pump)
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(session.runContentDescriptor)
            Disposer.dispose(console)
        }
        return relaunches.get()
    }

    fun `test the gutter offers one kind of breakpoint on a C source`() {
        val offered = XLineBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filterIsInstance<XLineBreakpointType<*>>()
            .filter { it.canPutAt(main, 3, project) }
            .map { it.id }
        val expected = if (GdbBreakpoints.clionLineType() != null) "com.jetbrains.cidr.execution.debugger.OCBreakpointType" else PebbleCLineBreakpointType.ID
        assertEquals(listOf(expected), offered)
        assertFalse(GdbBreakpoints.isAppSource(project, main.parent.parent.parent.findChild("package.json")!!))
        assertFalse("not on a blank line", PebbleCLineBreakpointType().canPutAt(main, 100, project))
        assertTrue(PebbleCLineBreakpointType.offersOwnType(clionPresent = false, cSource = true))
        assertFalse(PebbleCLineBreakpointType.offersOwnType(clionPresent = true, cSource = true))
    }

    /** A call as an agent makes it: off the UI thread, which the debugger's callbacks may need. */
    private fun <T> agentCall(block: suspend () -> T): T {
        val future = com.intellij.openapi.application.ApplicationManager.getApplication().executeOnPooledThread<T> { kotlinx.coroutines.runBlocking { block() } }
        val deadline = System.currentTimeMillis() + 20_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(10)
        }
        return future.get(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out:\n$output")
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
    }
}
