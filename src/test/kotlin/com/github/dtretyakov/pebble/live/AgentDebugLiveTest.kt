package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.agent.mcp.McpClient
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ui.RunContentManager
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import com.intellij.xdebugger.XDebuggerManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files

/**
 * An agent debugging a C app through the IDE's MCP server, against pebble-tool, gdb and the emery
 * emulator: a breakpoint set before the session and verified once gdb attaches, hit by a button
 * press, the stop read, an expression evaluated, a step, and the app let go.
 */
class AgentDebugLiveTest : IdeTestCase() {

    /** Off the UI thread, as an agent's calls are. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun runInDispatchThread(): Boolean = false

    fun `test an agent debugs a C app`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c").toRealPath()
        Files.writeString(
            root.resolve("src/c/fixture-c.c"),
            """
            #include <pebble.h>
            static Window *s_window;
            static int s_presses;
            static void select_clicked(ClickRecognizerRef recognizer, void *context) {
              s_presses++;
              APP_LOG(APP_LOG_LEVEL_INFO, "select %d", s_presses);
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
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        ApplicationManager.getApplication().invokeAndWait { PsiTestUtil.addContentRoot(module, rootFile) }
        Disposer.register(testRootDisposable) { ApplicationManager.getApplication().invokeAndWait { PsiTestUtil.removeContentEntry(module, rootFile) } }
        val server = McpServerService.getInstance()
        if (!server.isRunning) {
            server.start()
            Disposer.register(testRootDisposable) { server.stop() }
        }
        val client = McpClient(server.serverStreamUrl, mapOf("IJ_MCP_SERVER_PROJECT_PATH" to project.basePath!!))
        client.initialize()
        fun call(name: String, vararg arguments: Pair<String, Any>): JsonObject {
            val result = client.callTool(
                name,
                arguments.associate { (key, value) -> key to if (value is Number) JsonPrimitive(value) else if (value is Boolean) JsonPrimitive(value) else JsonPrimitive(value.toString()) },
                600,
            )
            assertFalse("$name: $result", result["isError"]?.jsonPrimitive?.boolean == true)
            return result["structuredContent"]?.jsonObject ?: Json.parseToJsonElement(result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        }

        try {
            // Line 5: `s_presses++;`
            assertEquals("pending", call("pebble_debug_breakpoint", "file" to "src/c/fixture-c.c", "line" to 5)["state"]!!.jsonPrimitive.content)

            val started = call("pebble_debug_start", "platform" to "emery")
            assertEquals(started.toString(), "running", started["state"]!!.jsonPrimitive.content)
            assertEquals("verified", call("pebble_debug_breakpoint", "file" to "src/c/fixture-c.c", "line" to 5)["state"]!!.jsonPrimitive.content)

            call("pebble_emulator", "platform" to "emery", "steps" to "press select", "screenshot" to false)
            val stop = call("pebble_debug_wait", "timeout" to 60)
            assertEquals(stop.toString(), "suspended", stop["state"]!!.jsonPrimitive.content)
            assertEquals("src/c/fixture-c.c", stop["position"]!!.jsonObject["file"]!!.jsonPrimitive.content)
            assertEquals(5, stop["position"]!!.jsonObject["line"]!!.jsonPrimitive.int)
            val frames = stop["frames"]!!.jsonArray.map { it.jsonObject }
            assertTrue(frames.first()["text"]!!.jsonPrimitive.content.startsWith("select_clicked"))
            assertTrue("main is down the stack", frames.any { it["text"]!!.jsonPrimitive.content.startsWith("main") && it["line"]?.jsonPrimitive?.int == 15 })
            assertEquals(listOf("recognizer", "context"), stop["variables"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content })

            assertEquals("41", call("pebble_debug_evaluate", "expression" to "s_presses + 41")["value"]!!.jsonPrimitive.content)

            val stepped = call("pebble_debug_control", "action" to "over")
            assertEquals(stepped.toString(), 6, stepped["position"]!!.jsonObject["line"]!!.jsonPrimitive.int)
            assertEquals("1", call("pebble_debug_evaluate", "expression" to "s_presses")["value"]!!.jsonPrimitive.content)

            assertEquals("running", call("pebble_debug_control", "action" to "resume")["state"]!!.jsonPrimitive.content)
            assertEquals("stopped", call("pebble_debug_stop")["state"]!!.jsonPrimitive.content)
        } finally {
            XDebuggerManager.getInstance(project).debugSessions.forEach { ApplicationManager.getApplication().invokeAndWait { it.stop() } }
            ExecutionManager.getInstance(project).getRunningProcesses().forEach {
                it.destroyProcess()
                it.waitFor(20_000)
            }
            ApplicationManager.getApplication().invokeAndWait {
                RunContentManager.getInstance(project).allDescriptors.forEach { Disposer.dispose(it) }
                EditorFactory.getInstance().allEditors.filter { it.editorKind == EditorKind.CONSOLE }.forEach { EditorFactory.getInstance().releaseEditor(it) }
            }
        }
    }
}
