package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.agent.mcp.McpClient
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.ExecutionManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files

/**
 * What an agent does with a C app, through the IDE's MCP server, against pebble-tool and the emery
 * emulator: build it, run it, read its logs, press a button, and look at the screen.
 */
class AgentLiveTest : IdeTestCase() {

    /** Off the UI thread, as an agent's calls are: the tools need it free, to save and refresh. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun runInDispatchThread(): Boolean = false

    fun `test an agent builds, runs, reads, presses and looks`() {
        if (System.getProperty("pebble.liveTests") != "true") return
        LiveSdk.tool()
        val root = LiveSdk.fixture("fixture-c").toRealPath()
        Files.writeString(
            root.resolve("src/c/fixture-c.c"),
            """
            #include <pebble.h>
            static Window *s_window;
            static TextLayer *s_text;
            static int s_presses;
            static void select_clicked(ClickRecognizerRef recognizer, void *context) {
              s_presses++;
              APP_LOG(APP_LOG_LEVEL_WARNING, "select %d", s_presses);
              text_layer_set_text(s_text, "Pressed");
            }
            static void click_config(void *context) {
              window_single_click_subscribe(BUTTON_ID_SELECT, select_clicked);
            }
            int main(void) {
              s_window = window_create();
              window_set_click_config_provider(s_window, click_config);
              s_text = text_layer_create(GRect(0, 80, 200, 40));
              text_layer_set_text(s_text, "Hello");
              layer_add_child(window_get_root_layer(s_window), text_layer_get_layer(s_text));
              window_stack_push(s_window, false);
              APP_LOG(APP_LOG_LEVEL_INFO, "agent fixture started");
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
                arguments.associate { (key, value) -> key to if (value is Number) JsonPrimitive(value) else JsonPrimitive(value.toString()) },
                600,
            )
            assertFalse("$name: $result", result["isError"]?.jsonPrimitive?.boolean == true)
            return result["structuredContent"]?.jsonObject ?: Json.parseToJsonElement(result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
        }

        try {
            val build = call("pebble_build")
            assertTrue(build.toString(), build["succeeded"]!!.jsonPrimitive.boolean)
            assertTrue(build["memory"]!!.jsonArray.any { it.jsonObject["platform"]!!.jsonPrimitive.content == "emery" })

            val run = call("pebble_run", "platform" to "emery")
            assertTrue(run.toString(), run["installed"]!!.jsonPrimitive.boolean)
            val after = run["logsAfter"]!!.jsonPrimitive.long

            fun logs(text: String): JsonObject? {
                val deadline = System.currentTimeMillis() + 30_000
                while (System.currentTimeMillis() < deadline) {
                    val page = call("pebble_logs", "after" to after, "contains" to text)
                    page["lines"]!!.jsonArray.firstOrNull()?.let { return it.jsonObject }
                    Thread.sleep(500)
                }
                return null
            }
            assertNotNull("the app's start was not logged", logs("agent fixture started"))

            val pressed = call("pebble_emulator", "platform" to "emery", "steps" to "press select\nwait 500")
            assertFalse(pressed.toString(), pressed["failed"]!!.jsonPrimitive.boolean)
            val shot = pressed["screenshot"]!!.jsonObject
            assertTrue(Files.exists(root.resolve(shot["file"]!!.jsonPrimitive.content)))
            assertEquals(200, shot["width"]!!.jsonPrimitive.int)
            val select = logs("select 1")
            assertNotNull("the press was not logged", select)
            assertEquals("WARNING", select!!["level"]!!.jsonPrimitive.content)

            val screen = call("pebble_screenshot")["screenshots"]!!.jsonArray.single().jsonObject
            assertEquals("emery", screen["platform"]!!.jsonPrimitive.content)
            assertTrue(screen["file"]!!.jsonPrimitive.content.startsWith("screenshots/emery_"))
        } finally {
            ExecutionManager.getInstance(project).getRunningProcesses().forEach {
                it.destroyProcess()
                it.waitFor(20_000)
            }
        }
    }
}
