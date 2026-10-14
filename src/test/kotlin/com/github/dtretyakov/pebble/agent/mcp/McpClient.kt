package com.github.dtretyakov.pebble.agent.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A minimal MCP client over Streamable HTTP, as an agent talks to the IDE: `initialize`, the
 * standing event stream, then JSON-RPC requests carrying the session id the server gave. Answers
 * may come as JSON or as server-sent events.
 */
class McpClient(private val url: String, private val headers: Map<String, String> = emptyMap()) {

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val ids = AtomicInteger()
    private var session: String? = null

    fun initialize(): JsonObject {
        val result = request(
            "initialize",
            buildJsonObject {
                put("protocolVersion", "2025-06-18")
                put("capabilities", JsonObject(emptyMap()))
                put("clientInfo", buildJsonObject { put("name", "pebble-test"); put("version", "1") })
            },
        )
        notify("notifications/initialized")
        listen()
        return result
    }

    /**
     * The standing stream a client opens for what the server sends unasked, as agents do: the
     * IDE's server drops a session that has not opened it within seconds.
     */
    private fun listen() {
        val builder = HttpRequest.newBuilder(URI(url)).header("Accept", "text/event-stream").GET()
        session?.let { builder.header("mcp-session-id", it) }
        headers.forEach { (key, value) -> builder.header(key, value) }
        http.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofLines()).thenAccept { response -> response.body().forEach { } }
    }

    fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutSeconds: Long = 120): JsonObject {
        val id = ids.incrementAndGet()
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        val response = send(body, timeoutSeconds)
        response.headers().firstValue("mcp-session-id").ifPresent { session = it }
        val text = response.body()
        val json = if (text.trimStart().startsWith("{")) text else
            text.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .lastOrNull { runCatching { Json.parseToJsonElement(it).jsonObject["id"]?.toString() == id.toString() }.getOrDefault(false) }
                ?: throw AssertionError("$method: no answer in HTTP ${response.statusCode()}: ${text.take(2000)}")
        val message = Json.parseToJsonElement(json).jsonObject
        message["error"]?.let { throw AssertionError("$method failed: $it") }
        return message["result"]!!.jsonObject
    }

    fun callTool(name: String, arguments: Map<String, JsonElement> = emptyMap(), timeoutSeconds: Long = 120): JsonObject =
        request("tools/call", buildJsonObject { put("name", name); put("arguments", JsonObject(arguments)) }, timeoutSeconds)

    private fun notify(method: String) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }, 10)
    }

    private fun send(body: JsonObject, timeoutSeconds: Long): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        session?.let { builder.header("mcp-session-id", it) }
        headers.forEach { (key, value) -> builder.header(key, value) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }
}
