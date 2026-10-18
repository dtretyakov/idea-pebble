package com.github.dtretyakov.pebble.live

import com.github.dtretyakov.pebble.agent.mcp.McpClient
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
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
            // What the agent looks at: the same screen, three times the size.
            val view = javax.imageio.ImageIO.read(root.resolve(shot["view"]!!.jsonPrimitive.content).toFile())
            assertEquals(600, view.width)
            assertEquals(684, view.height)
            val select = logs("select 1")
            assertNotNull("the press was not logged", select)
            assertEquals("WARNING", select!!["level"]!!.jsonPrimitive.content)

            val screen = call("pebble_screenshot")["screenshots"]!!.jsonArray.single().jsonObject
            assertEquals("emery", screen["platform"]!!.jsonPrimitive.content)
            assertTrue(screen["file"]!!.jsonPrimitive.content.startsWith("build/preview/emery_"))

            // A scenario: recorded the first time, passed the next, and its screen accepted.
            root.resolve("tests/ui").also { Files.createDirectories(it) }.resolve("hello.pbtest")
                .let { Files.writeString(it, "platforms emery\ninstall\nwait 1500\nexpect hello\n") }
            val first = call("pebble_ui_tests")
            assertEquals(first.toString(), 1, first["recorded"]!!.jsonPrimitive.int)
            val recordedView = first["tests"]!!.jsonArray.single().jsonObject["view"]!!.jsonPrimitive.content
            assertTrue(recordedView, Files.exists(root.resolve(recordedView)))
            assertTrue(Files.exists(root.resolve("tests/ui/screenshots/emery/hello.png")))
            val second = call("pebble_ui_tests", "path" to "tests/ui/hello.pbtest")
            assertEquals(second.toString(), 1, second["passed"]!!.jsonPrimitive.int)
            val accepted = call("pebble_ui_test_accept", "scenario" to "tests/ui/hello.pbtest", "platform" to "emery", "screen" to "hello")
            assertEquals("tests/ui/screenshots/emery/hello.png", accepted["reference"]!!.jsonPrimitive.content)

            // The release check of the same app: a release build, every target platform in it, and
            // nothing about screenshots, which are pebble publish's.
            val release = call("pebble_release_check")["items"]!!.jsonArray.map { it.jsonObject }
            fun item(check: String) = release.filter { it["check"]!!.jsonPrimitive.content == check }
            assertEquals(release.toString(), "ok", item("platforms").single()["severity"]!!.jsonPrimitive.content)
            assertEquals("ok", item("bundle size").single()["severity"]!!.jsonPrimitive.content)
            assertEquals("ok", item("uuid").single()["severity"]!!.jsonPrimitive.content)
            assertTrue(release.toString(), item("screenshots").isEmpty())

            val sdk = call("pebble_sdk")
            assertTrue(sdk.toString(), sdk["current"]!!.jsonPrimitive.content in sdk["installed"]!!.jsonArray.map { it.jsonPrimitive.content })

            // Last, as an agent would when an emulator is stuck: every emulator gone, its data with it.
            call("pebble_emulator_reset", "action" to "wipe")
            assertTrue(com.github.dtretyakov.pebble.emulator.EmulatorState.running().isEmpty())
        } finally {
            ExecutionManager.getInstance(project).getRunningProcesses().forEach {
                it.destroyProcess()
                it.waitFor(20_000)
            }
            // The runs' consoles, which a headless IDE shows in no Run window that would dispose of them.
            ApplicationManager.getApplication().invokeAndWait {
                RunContentManager.getInstance(project).allDescriptors.forEach { Disposer.dispose(it) }
                EditorFactory.getInstance().allEditors.filter { it.editorKind == EditorKind.CONSOLE }.forEach { EditorFactory.getInstance().releaseEditor(it) }
            }
        }
    }
}
