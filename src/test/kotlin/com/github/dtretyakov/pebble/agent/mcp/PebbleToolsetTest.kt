package com.github.dtretyakov.pebble.agent.mcp

import com.github.dtretyakov.pebble.agent.LogBuffer
import com.github.dtretyakov.pebble.agent.PebbleRunLogs
import com.github.dtretyakov.pebble.testing.IdeTestCase
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.newvfs.impl.VfsRootAccess
import com.intellij.testFramework.PsiTestUtil
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * The Pebble tools as an agent meets them: the IDE's own MCP server started, and a client talking
 * to it over HTTP — listing the tools, and calling them on a Pebble project.
 */
class PebbleToolsetTest : IdeTestCase() {

    private lateinit var client: McpClient

    override fun setUp() {
        super.setUp()
        val root = Files.createTempDirectory("mcp").toRealPath().resolve("watchapp")
        VfsRootAccess.allowRootAccess(testRootDisposable, root.parent.toString())
        root.createDirectories().resolve("package.json").writeText(
            """{"name": "watchapp", "pebble": {"projectType": "native", "uuid": "1d1a3a2e-0000-4000-8000-000000000001", "targetPlatforms": ["basalt", "chalk", "emery"], "watchapp": {"watchface": false}}}""",
        )
        val rootFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)!!
        PsiTestUtil.addContentRoot(module, rootFile)
        Disposer.register(testRootDisposable) { PsiTestUtil.removeContentEntry(module, rootFile) }

        val server = McpServerService.getInstance()
        if (!server.isRunning) {
            server.start()
            Disposer.register(testRootDisposable) { server.stop() }
        }
        client = McpClient(server.serverStreamUrl, mapOf("IJ_MCP_SERVER_PROJECT_PATH" to project.basePath!!))
        client.initialize()
    }

    fun `test the tools are listed, each saying when to call it`() {
        val tools = client.request("tools/list")["tools"]!!.jsonArray.map { it.jsonObject }.associateBy { it["name"]!!.jsonPrimitive.content }
        val pebble = tools.keys.filter { it.startsWith("pebble_") }.toSet()
        assertEquals(
            setOf("pebble_project", "pebble_build", "pebble_run", "pebble_logs", "pebble_screenshot", "pebble_emulator", "pebble_api", "pebble_ui_tests", "pebble_ui_test_accept", "pebble_emulator_reset", "pebble_sdk",
                "pebble_debug_start", "pebble_debug_breakpoint", "pebble_debug_wait", "pebble_debug_control", "pebble_debug_evaluate", "pebble_debug_stop"),
            pebble,
        )
        for (name in pebble) {
            val description = tools.getValue(name)["description"]!!.jsonPrimitive.content
            assertFalse("$name: $description", description.contains("|") || description.isBlank())
        }
        val emulator = tools.getValue("pebble_emulator")["inputSchema"]!!.jsonObject
        assertEquals(listOf("steps"), emulator["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    fun `test the project is described`() {
        val result = structured(client.callTool("pebble_project"))
        assertEquals("native", result["type"]!!.jsonPrimitive.content)
        assertEquals(listOf("basalt", "chalk", "emery"), result["targetPlatforms"]!!.jsonArray.map { it.jsonPrimitive.content })
        val chalk = result["platforms"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "chalk" }
        assertTrue(chalk["round"]!!.jsonPrimitive.boolean)
    }

    fun `test logs are read on from a cursor, with each app line's level`() {
        val buffer = PebbleRunLogs.getInstance(project).buffer
        val start = buffer.position - 1
        buffer.append("\u001b[1m\u001b[31m[06:03:16] main.c:4> a warning\u001b[0m\n[06:03:16] main.c:5> an info\n", LogBuffer.Source.APP)
        buffer.append("The app exited with 104 bytes still allocated.\n", LogBuffer.Source.IDE)

        val all = structured(client.callTool("pebble_logs", mapOf("after" to JsonPrimitive(start))))
        val lines = all["lines"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("[06:03:16] main.c:4> a warning", "[06:03:16] main.c:5> an info", "The app exited with 104 bytes still allocated."), lines.map { it["text"]!!.jsonPrimitive.content })
        assertEquals("WARNING", lines[0]["level"]!!.jsonPrimitive.content)
        assertEquals("ide", lines[2]["source"]!!.jsonPrimitive.content)

        val next = all["next"]!!.jsonPrimitive.long
        assertTrue(structured(client.callTool("pebble_logs", mapOf("after" to JsonPrimitive(next))))["lines"]!!.jsonArray.isEmpty())
        val warnings = structured(client.callTool("pebble_logs", mapOf("after" to JsonPrimitive(start), "level" to JsonPrimitive("warning"))))
        assertEquals(2, warnings["lines"]!!.jsonArray.size)
    }

    fun `test what cannot be done is said, not thrown`() {
        val badStep = client.callTool("pebble_emulator", mapOf("steps" to JsonPrimitive("press start"), "platform" to JsonPrimitive("emery")))
        assertTrue(badStep.toString(), badStep["isError"]?.jsonPrimitive?.boolean == true)
        val text = (badStep["content"] as JsonArray).joinToString { it.jsonObject["text"]!!.jsonPrimitive.content }
        assertTrue(text, "emulator is running" in text || "'press' takes a button" in text)

        val notDebugging = client.callTool("pebble_debug_control", mapOf("action" to JsonPrimitive("over")))
        assertTrue(notDebugging.toString(), "No Pebble debug session" in notDebugging.toString())
        assertEquals("none", structured(client.callTool("pebble_debug_wait", mapOf("timeout" to JsonPrimitive(1))))["state"]!!.jsonPrimitive.content)

        val badReset = client.callTool("pebble_emulator_reset", mapOf("action" to JsonPrimitive("everything")))
        assertTrue(badReset.toString(), "Actions: kill, wipe." in badReset.toString())

        val noScenarios = client.callTool("pebble_ui_tests")
        assertTrue(noScenarios.toString(), noScenarios["isError"]!!.jsonPrimitive.boolean && "No .pbtest scenarios at tests/ui" in noScenarios.toString())

        val wrongPlatform = client.callTool("pebble_run", mapOf("platform" to JsonPrimitive("aplite")))
        assertTrue(wrongPlatform["isError"]!!.jsonPrimitive.boolean)
        assertTrue(wrongPlatform.toString(), "does not target aplite" in wrongPlatform.toString())
    }

    fun `test a build answers with what pebble build reported`() {
        // The test holds the UI thread, which the build saves documents on: the call goes from another.
        val future = java.util.concurrent.CompletableFuture.supplyAsync { client.callTool("pebble_build", timeoutSeconds = 120) }
        val deadline = System.currentTimeMillis() + 120_000
        while (!future.isDone && System.currentTimeMillis() < deadline) {
            com.intellij.util.ui.UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(20)
        }
        val result = future.get(1, java.util.concurrent.TimeUnit.SECONDS)
        val build = if (result["isError"]?.jsonPrimitive?.boolean == true) null else structured(result)
        // No sources: the build fails, or without pebble-tool does not start; either way it is said.
        assertTrue(result.toString(), build == null || !build["succeeded"]!!.jsonPrimitive.boolean)
    }

    private fun structured(result: JsonObject): JsonObject {
        assertFalse(result.toString(), result["isError"]?.jsonPrimitive?.boolean == true)
        return result["structuredContent"]?.jsonObject
            ?: kotlinx.serialization.json.Json.parseToJsonElement(result["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content).jsonObject
    }
}
