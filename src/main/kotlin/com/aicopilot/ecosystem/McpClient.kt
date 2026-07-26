package com.aicopilot.ecosystem

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * MCP（Model Context Protocol）客户端（应用级，持久化服务器配置）。
 *
 * Phase 6「开放生态」的协议集成层：通过 stdio 或 SSE 传输与 MCP 服务器建立 JSON-RPC 2.0 会话，
 * 完成 `initialize` 握手后列出并调用远端工具；远端工具可经 Agent 工具（mcp_list / mcp_call）暴露给模型。
 *
 * 设计要点：
 *  - stdio 传输为完整实现（本地 MCP 服务器主流方式）。
 *  - SSE 传输为尽力实现（Java 11+ HttpClient 读取事件流 + POST 回传）。
 *  - 所有网络/进程操作需在非 EDT 线程调用（见 [connectAsync]）。
 */
@State(
    name = "com.aicopilot.ecosystem.McpClient",
    storages = [Storage("ai-copilot-mcp.xml")]
)
class McpClient : PersistentStateComponent<McpClient.State> {

    enum class Transport { STDIO, SSE }

    data class McpServerConfig(
        var name: String = "",
        var transport: String = Transport.STDIO.name,
        var command: String = "",          // stdio: 可执行命令
        var args: String = "",             // stdio: 空格分隔参数
        var env: String = "",              // stdio: KEY=VALUE;... 额外环境变量
        var url: String = "",              // sse: 事件流地址
        var enabled: Boolean = true
    )

    data class McpTool(
        var name: String = "",
        var description: String = "",
        var inputSchema: String = "{}"
    )

    class State {
        @XCollection(style = XCollection.Style.v2)
        var configs: MutableList<McpServerConfig> = mutableListOf()
    }

    private val state = State()
    private val gson = Gson()
    private val connections = ConcurrentHashMap<String, Connection>()
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(15))
        .build()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    // ===== 配置持久化 =====
    fun getConfigs(): List<McpServerConfig> = state.configs.toList()
    fun saveConfig(cfg: McpServerConfig) {
        val idx = state.configs.indexOfFirst { it.name == cfg.name }
        if (idx >= 0) state.configs[idx] = cfg else state.configs.add(cfg)
    }

    fun removeConfig(name: String) {
        state.configs.removeAll { it.name == name }
        disconnect(name)
    }

    // ===== 连接管理 =====
    fun isConnected(name: String): Boolean = connections[name]?.isConnected() == true

    fun listServers(): List<String> = connections.keys.toList()

    fun listTools(server: String): List<McpTool> =
        connections[server]?.tools() ?: emptyList()

    /**
     * 异步连接（不在 EDT 调用）。
     */
    fun connectAsync(config: McpServerConfig, onResult: (Result<Unit>) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            onResult(runCatching { connect(config) })
        }
    }

    @Synchronized
    fun connect(config: McpServerConfig): Unit {
        if (config.name.isBlank()) throw IllegalArgumentException("server name required")
        disconnect(config.name)
        val conn = if (config.transport == Transport.SSE.name) SseConnection(config, gson, httpClient)
        else StdioConnection(config, gson)
        conn.handshake()
        connections[config.name] = conn
        saveConfig(config)
    }

    fun callTool(server: String, toolName: String, argsJson: String): Result<String> = runCatching {
        val conn = connections[server] ?: throw IllegalStateException("not connected: $server")
        conn.callTool(toolName, argsJson)
    }

    @Synchronized
    fun disconnect(name: String) {
        connections.remove(name)?.close()
    }

    fun disconnectAll() {
        connections.values.toList().forEach { it.close() }
        connections.clear()
    }

    // ===== 连接抽象 =====
    interface Connection {
        fun handshake()
        fun callTool(name: String, argsJson: String): String
        fun tools(): List<McpTool>
        fun isConnected(): Boolean
        fun close()
    }

    /**
     * stdio 传输：启动子进程，按行读取 JSON-RPC，stdin 按行写回。
     */
    private class StdioConnection(
        private val config: McpServerConfig,
        private val gson: Gson
    ) : Connection {
        private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
        private val nextId = AtomicLong(1)
        private val toolList = mutableListOf<McpTool>()
        private val lock = Any()

        private lateinit var process: Process
        private lateinit var writer: BufferedWriter
        private lateinit var reader: BufferedReader

        @Volatile
        private var alive = false

        override fun handshake() {
            val cmd = config.command.ifBlank { throw IllegalArgumentException("stdio command required") }
            val args = config.args.split(Regex("\\s+")).filter { it.isNotBlank() }
            val pb = ProcessBuilder(listOf(cmd) + args)
            if (config.env.isNotBlank()) {
                val env = pb.environment()
                config.env.split(";").forEach { pair ->
                    val eq = pair.indexOf('=')
                    if (eq > 0) env[pair.substring(0, eq)] = pair.substring(eq + 1)
                }
            }
            process = pb.start()
            writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
            reader = process.inputStream.bufferedReader(Charsets.UTF_8)

            // 读取线程
            Thread({
                try {
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val raw = line ?: continue
                        if (raw.isBlank()) continue
                        handleLine(raw)
                    }
                } catch (_: Exception) {
                } finally {
                    alive = false
                }
            }, "mcp-stdio-${config.name}").apply { isDaemon = true; start() }

            alive = true
            initAndList()
        }

        private fun handleLine(raw: String) {
            val json = try {
                JsonParser.parseString(raw).asJsonObject
            } catch (_: Exception) {
                return
            }
            if (json.has("id")) {
                val id = json.get("id").asLong
                pending.remove(id)?.complete(json)
            }
            // 通知（含 id）忽略
        }

        private fun send(method: String, params: JsonObject, notification: Boolean): CompletableFuture<JsonObject>? {
            val id = if (notification) null else nextId.getAndIncrement()
            val req = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                if (id != null) addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            }
            synchronized(lock) {
                writer.write(gson.toJson(req) + "\n")
                writer.flush()
            }
            if (notification || id == null) return null
            val fut = CompletableFuture<JsonObject>()
            pending[id] = fut
            return fut
        }

        private fun initAndList() {
            val initParams = JsonObject().apply {
                addProperty("protocolVersion", "2024-11-05")
                add("capabilities", JsonObject())
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "idea-ai")
                    addProperty("version", "1.0.0")
                })
            }
            send("initialize", initParams, false)?.get(30, TimeUnit.SECONDS)
            send("notifications/initialized", JsonObject(), true)
            val resp = send("tools/list", JsonObject(), false)?.get(30, TimeUnit.SECONDS)
            val arr = resp?.getAsJsonObject("result")?.getAsJsonArray("tools")
            arr?.forEach { el ->
                val o = el.asJsonObject
                toolList.add(
                    McpTool(
                        name = o.get("name")?.asString ?: "",
                        description = o.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        inputSchema = o.get("inputSchema")?.toString() ?: "{}"
                    )
                )
            }
        }

        override fun callTool(name: String, argsJson: String): String {
            val params = JsonObject().apply {
                addProperty("name", name)
                add("arguments", try {
                    JsonParser.parseString(argsJson)
                } catch (_: Exception) {
                    JsonObject()
                })
            }
            val resp = send("tools/call", params, false)?.get(60, TimeUnit.SECONDS)
                ?: return "ERROR: no response from MCP server"
            if (resp.has("error")) {
                return "ERROR: ${resp.getAsJsonObject("error").get("message")?.asString ?: "unknown"}"
            }
            val content = resp.getAsJsonObject("result")?.getAsJsonArray("content")
            val sb = StringBuilder()
            content?.forEach { el ->
                val o = el.asJsonObject
                if (o.get("type")?.asString == "text") {
                    sb.append(o.get("text")?.asString ?: "")
                    sb.append("\n")
                }
            }
            return sb.toString().trim()
        }

        override fun tools(): List<McpTool> = toolList.toList()
        override fun isConnected(): Boolean = alive && ::process.isInitialized && process.isAlive
        override fun close() {
            alive = false
            runCatching { writer.close() }
            runCatching { process.destroyForcibly() }
        }
    }

    /**
     * SSE 传输（尽力实现）：GET 事件流 + 捕获 endpoint 事件后 POST 回传。
     */
    private class SseConnection(
        private val config: McpServerConfig,
        private val gson: Gson,
        private val http: HttpClient
    ) : Connection {
        private val pending = ConcurrentHashMap<Long, CompletableFuture<JsonObject>>()
        private val nextId = AtomicLong(1)
        private val toolList = mutableListOf<McpTool>()
        private val lock = Any()

        @Volatile
        private var postUrl: String? = null

        @Volatile
        private var alive = false

        override fun handshake() {
            val base = config.url.ifBlank { throw IllegalArgumentException("sse url required") }
            val req = HttpRequest.newBuilder()
                .uri(URI.create(base))
                .header("Accept", "text/event-stream")
                .GET()
                .build()
            val resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
            alive = true
            // 读取 SSE 流（endpoint 事件给出 POST 地址；其余为响应）
            Thread({
                try {
                    resp.body().bufferedReader(Charsets.UTF_8).use { r ->
                        var line: String?
                        var dataBuf = StringBuilder()
                        while (r.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            when {
                                l.startsWith("event:") -> dataBuf = StringBuilder()
                                l.startsWith("data:") -> {
                                    val data = l.removePrefix("data:").trim()
                                    handleSseData(data)
                                }
                                l.isBlank() -> dataBuf = StringBuilder()
                            }
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    alive = false
                }
            }, "mcp-sse-${config.name}").apply { isDaemon = true; start() }

            // 等待 endpoint 事件（最多 10s）
            val deadline = System.currentTimeMillis() + 10_000
            while (postUrl == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            if (postUrl == null) throw IllegalStateException("SSE endpoint not received")
            initAndList()
        }

        private fun handleSseData(data: String) {
            // endpoint 事件：后续 POST 的目标
            if (data.startsWith("http")) {
                postUrl = if (data.contains("://")) data else "${config.url.removeSuffix("/")}/$data"
                return
            }
            if (data.startsWith("{")) {
                val json = try {
                    JsonParser.parseString(data).asJsonObject
                } catch (_: Exception) {
                    return
                }
                if (json.has("id")) {
                    val id = json.get("id").asLong
                    pending.remove(id)?.complete(json)
                }
            }
        }

        private fun post(method: String, params: JsonObject, notification: Boolean): CompletableFuture<JsonObject>? {
            val id = if (notification) null else nextId.getAndIncrement()
            val reqJson = JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                if (id != null) addProperty("id", id)
                addProperty("method", method)
                add("params", params)
            }
            val url = postUrl ?: throw IllegalStateException("no SSE endpoint")
            val httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(reqJson)))
                .build()
            http.send(httpReq, HttpResponse.BodyHandlers.ofString())
            if (notification || id == null) return null
            val fut = CompletableFuture<JsonObject>()
            pending[id] = fut
            return fut
        }

        private fun initAndList() {
            val initParams = JsonObject().apply {
                addProperty("protocolVersion", "2024-11-05")
                add("capabilities", JsonObject())
                add("clientInfo", JsonObject().apply {
                    addProperty("name", "idea-ai")
                    addProperty("version", "1.0.0")
                })
            }
            post("initialize", initParams, false)?.get(30, TimeUnit.SECONDS)
            post("notifications/initialized", JsonObject(), true)
            val resp = post("tools/list", JsonObject(), false)?.get(30, TimeUnit.SECONDS)
            val arr = resp?.getAsJsonObject("result")?.getAsJsonArray("tools")
            arr?.forEach { el ->
                val o = el.asJsonObject
                toolList.add(
                    McpTool(
                        name = o.get("name")?.asString ?: "",
                        description = o.get("description")?.takeIf { !it.isJsonNull }?.asString ?: "",
                        inputSchema = o.get("inputSchema")?.toString() ?: "{}"
                    )
                )
            }
        }

        override fun callTool(name: String, argsJson: String): String {
            val params = JsonObject().apply {
                addProperty("name", name)
                add("arguments", try {
                    JsonParser.parseString(argsJson)
                } catch (_: Exception) {
                    JsonObject()
                })
            }
            val resp = post("tools/call", params, false)?.get(60, TimeUnit.SECONDS)
                ?: return "ERROR: no response from MCP server"
            if (resp.has("error")) {
                return "ERROR: ${resp.getAsJsonObject("error").get("message")?.asString ?: "unknown"}"
            }
            val content = resp.getAsJsonObject("result")?.getAsJsonArray("content")
            val sb = StringBuilder()
            content?.forEach { el ->
                val o = el.asJsonObject
                if (o.get("type")?.asString == "text") sb.append(o.get("text")?.asString ?: "").append("\n")
            }
            return sb.toString().trim()
        }

        override fun tools(): List<McpTool> = toolList.toList()
        override fun isConnected(): Boolean = alive
        override fun close() {
            alive = false
        }
    }

    companion object {
        fun getInstance(): McpClient =
            ApplicationManager.getApplication().getService(McpClient::class.java)
    }
}
