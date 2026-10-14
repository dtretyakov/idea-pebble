package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.debug.gdb.GdbDebugProcess
import com.github.dtretyakov.pebble.debug.gdb.GdbSetup
import com.github.dtretyakov.pebble.emulator.EmulatorState
import com.github.dtretyakov.pebble.run.PebbleInstallCommand
import com.github.dtretyakov.pebble.run.PebbleLogProcessHandler
import com.github.dtretyakov.pebble.run.PebbleRunState
import com.github.dtretyakov.pebble.run.PebbleRunTarget
import com.github.dtretyakov.pebble.sdk.PebbleDataDir
import com.github.dtretyakov.pebble.sdk.PebbleSdks
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugProcessStarter
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerTestUtil
import com.intellij.xdebugger.frame.XNamedValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Debug for C as the run does it, against pebble-tool and the emery emulator: the install with
 * logs, gdb attached when pebble-tool says the app is installed, a breakpoint in a click handler,
 * select pressed, the stack, variables and an expression read, a step, the app let go, and gdb
 * detached when the run stops — the app running on without it.
 *
 * Also checks that pebble-tool's own `pebble gdb` still attaches the way [GdbSetup] copies it.
 */
class GdbDebugLiveTest : IdeTestCase() {

    fun `test the IDE debugs a C app in the emulator through gdb`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c").toRealPath()
        val source = root.resolve("src/c/fixture-c.c")
        Files.writeString(
            source,
            """
            #include <pebble.h>
            static Window *s_window;
            typedef struct { int count; GPoint where; } Clicks;
            static Clicks s_clicks = { 0, { 3, 4 } };
            static void select_clicked(ClickRecognizerRef recognizer, void *context) {
              Clicks *clicks = &s_clicks;
              clicks->count += 1;
              APP_LOG(APP_LOG_LEVEL_INFO, "select %d", clicks->count);
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
        assertEquals(0, LiveSdk.run(root, "build", "--debug").exitCode)
        // emery up first, past pebble-tool's few seconds' wait for a cold one.
        LiveSdk.install(root, "--emulator", "emery", "--vnc")

        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }
        val main = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)!!
        // Line 7: `clicks->count += 1;`
        assertNotNull(XDebuggerTestUtil.toggleBreakpoint(project, main, 6))

        val handler = PebbleRunState.processHandler(
            tool, PebbleInstallCommand.arguments(PebbleRunTarget.EMULATOR, "emery", "", "", logs = true), root,
        ) as PebbleLogProcessHandler
        val output = StringBuffer()
        handler.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    output.append(event.text)
                }
            },
        )
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        console.attachToProcess(handler)
        lateinit var process: GdbDebugProcess
        val session = XDebuggerManager.getInstance(project).startSessionAndShowTab(
            "C",
            null as RunContentDescriptor?,
            object : XDebugProcessStarter() {
                override fun start(session: XDebugSession): XDebugProcess {
                    process = GdbDebugProcess(session, DefaultExecutionResult(console, handler), setup = {
                        GdbSetup.prepare(PebbleSdks.scan(PebbleDataDir.locate()), root, "emery", EmulatorState.running())
                    })
                    handler.beforeDestroy = { process.detach() }
                    return process
                }
            },
        )
        if (!handler.isStartNotified) handler.startNotify()
        try {
            waitUntil(240, output) { "gdb is attached" in output }
            Thread.sleep(1000)
            assertEquals(0, LiveSdk.run(root, "emu-button", "click", "select", "--emulator", "emery", "--vnc").exitCode)
            waitUntil(30, output) { session.isSuspended }
            assertEquals(main, session.currentPosition!!.file)
            assertEquals(6, session.currentPosition!!.line)

            val frames = XDebuggerTestUtil.collectFrames(session)
            assertTrue(frames.size > 3)
            assertEquals(main, frames[0].sourcePosition!!.file)
            assertNull("the firmware's sources are not on this disk", frames[1].sourcePosition)
            assertTrue("main is down the stack", frames.any { it.sourcePosition?.file == main && it.sourcePosition!!.line == 16 })

            val locals = XDebuggerTestUtil.collectChildren(frames[0]).filterIsInstance<XNamedValue>().associateBy { it.name }
            assertTrue(locals.keys.toString(), locals.keys.containsAll(listOf("recognizer", "context", "clicks")))
            val fields = XDebuggerTestUtil.collectChildren(locals.getValue("clicks")).filterIsInstance<XNamedValue>()
            assertEquals(listOf("count", "where"), fields.map { it.name })
            assertEquals("0", XDebuggerTestUtil.computePresentation(fields[0]).myValue)

            val where = XDebuggerTestUtil.evaluate(session, "s_clicks.where.x + s_clicks.where.y")
            assertNull(where.second, where.second)
            assertEquals("7", XDebuggerTestUtil.computePresentation(where.first).myValue)
            assertTrue(XDebuggerTestUtil.evaluate(session, "nope").second!!.isNotEmpty())

            session.stepOver(false)
            waitUntil(10, output) { session.isSuspended && session.currentPosition?.line == 7 }
            assertEquals("1", XDebuggerTestUtil.computePresentation(XDebuggerTestUtil.evaluate(session, "s_clicks.count").first).myValue)

            // Off the end of the handler — after its closing brace, if gdb stops there — the app runs
            // on through the firmware rather than stopping in it.
            session.stepOver(false)
            settle()
            if (session.isSuspended && session.currentPosition?.line == 8) {
                session.stepOver(false)
                settle()
            }
            assertFalse("stopped at ${session.currentPosition}", session.isSuspended)
            waitUntil(10, output) { "select 1" in output }

            // Pressed again, it stops again: the app ran on.
            assertEquals(0, LiveSdk.run(root, "emu-button", "click", "select", "--emulator", "emery", "--vnc").exitCode)
            waitUntil(30, output) { session.isSuspended }
            session.resume()
            waitUntil(10, output) { "select 2" in output }
        } finally {
            handler.destroyProcess()
            handler.waitFor(20_000)
            session.stop()
            UIUtil.dispatchAllInvocationEvents()
            Disposer.dispose(session.runContentDescriptor)
            Disposer.dispose(console)
        }
        // Detached: the app takes the button by itself, with no breakpoint in the way.
        val logs = PebbleRunState.processHandler(
            tool, listOf("logs") + PebbleInstallCommand.connection(PebbleRunTarget.EMULATOR, "emery", phoneAddress = "", adbSerial = ""), root,
        )
        val after = StringBuffer()
        logs.addProcessListener(
            object : ProcessListener {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    after.append(event.text)
                }
            },
        )
        logs.startNotify()
        try {
            Thread.sleep(2000)
            assertEquals(0, LiveSdk.run(root, "emu-button", "click", "select", "--emulator", "emery", "--vnc").exitCode)
            waitUntil(20, after) { "select 3" in after }
        } finally {
            logs.destroyProcess()
            logs.waitFor(10_000)
        }
    }

    fun `test pebble gdb still attaches the way the debugger copies it`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        val tool = LiveSdk.tool()
        val python = Path.of(Files.readAllLines(tool.toRealPath()).first().removePrefix("#!").trim())
        val debugPy = Files.walk(python.parent.parent).use { paths ->
            paths.filter { it.endsWith(Path.of("pebble_tool/commands/sdk/project/debug.py")) }.findFirst().orElse(null)
        } ?: return fail("no pebble_tool/commands/sdk/project/debug.py beside $python")
        val text = debugPy.readText()
        for (expected in listOf(
            "'*(void**)&g_app_load_address'",
            "'*(void**)&g_worker_load_address'",
            "'{}_sdk_debug.elf'.format(platform)",
            "os.path.join(sdk_root, 'pebble', platform, 'qemu',",
            "os.path.join(os.getcwd(), 'build', platform, 'pebble-app.elf')",
            "os.path.join(os.getcwd(), 'build', platform, 'pebble-worker.elf')",
            "['arm-none-eabi-objdump', '--headers', '--wide',",
            "if 'ALLOC' in section.flags",
            "\"target remote :{}\".format(gdb_port)",
            "\"break app_crashed\"",
            "'{base_addr}+{text:#x}'",
            "'-s {section} {base_addr}+{offset:#x}'",
        )) {
            assertTrue("pebble gdb no longer has $expected in $debugPy", expected in text)
        }
    }

    private fun settle() {
        repeat(30) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
        }
    }

    private fun waitUntil(seconds: Int, output: CharSequence, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("timed out:\n$output")
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(50)
        }
    }
}
