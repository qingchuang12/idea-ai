package com.aicopilot.service

import com.aicopilot.model.*
import com.aicopilot.settings.AIApplicationSettings
import com.aicopilot.settings.ModelRegistry
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 聊天服务 - 支持多驱动模式 + 多模型 BYOK 路由
 */
class ChatService {
    
    private val settings = AIApplicationSettings.getInstance()
    private val gson = Gson()

    /** 每次构建都读取最新代理/超时设置（ChatService 为轻量瞬时实例）。 */
    private fun buildClient(): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        val host = settings.httpProxyHost.trim()
        val port = settings.httpProxyPort
        if (host.isNotBlank() && port > 0) {
            b.proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(host, port)))
        }
        return b.build()
    }

    private val client = buildClient()

    /**
     * 解析当前激活模型；若无则回退到 legacy 设置字段构建的临时模型。
     */
    fun activeModel(): ModelConfig {
        return ModelRegistry.getInstance().getActiveModel() ?: ModelConfig(
            alias = "Legacy",
            baseUrl = settings.baseUrl,
            apiKey = settings.apiKey,
            modelName = settings.modelName,
            headersJson = settings.customHeaders.ifBlank { "{}" },
            bodyTemplate = settings.requestBodyTemplate
        )
    }
    
    /**
     * 发送聊天请求（流式）
     */
    suspend fun sendChatStream(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>,
        systemPrompt: String? = null
    ): Flow<String> = flow {
        when (settings.getCurrentDriverMode()) {
            AIApplicationSettings.DriverMode.NATIVE_API -> {
                emitAll(sendNativeApiStream(messages, contextItems, activeModel(), systemPrompt = systemPrompt))
            }
            AIApplicationSettings.DriverMode.LOCAL_OLLAMA -> {
                emitAll(sendOllamaStream(messages, contextItems, systemPrompt))
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 使用指定模型发送流式请求（供 Agent / 多角色协同显式指定模型）。
     */
    suspend fun sendChatStreamWithModel(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>,
        model: ModelConfig,
        extraBodyJson: String? = null
    ): Flow<String> = flow {
        emitAll(sendNativeApiStream(messages, contextItems, model, extraBodyJson))
    }.flowOn(Dispatchers.IO)
    
    /**
     * 原生 API 模式 - 流式请求
     */
    private fun sendNativeApiStream(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>,
        model: ModelConfig,
        extraBodyJson: String? = null,
        systemPrompt: String? = null
    ): Flow<String> = callbackFlow {
        try {
            val url = "${model.baseUrl.trimEnd('/')}/chat/completions"
            
            // 构建请求体
            val requestMessages = buildRequestMessages(messages, contextItems, model, systemPrompt)
            val requestBody = buildRequestBody(requestMessages, model, extraBodyJson)
            
            // 构建 Headers
            val headers = Headers.Builder()
                .add("Content-Type", "application/json")
            if (model.apiKey.isNotBlank()) {
                headers.add("Authorization", "Bearer ${model.apiKey}")
            }
            
            // 添加自定义 Headers
            try {
                val customHeaders = JsonParser.parseString(model.headersJson).asJsonObject
                customHeaders.entrySet().forEach { (key, value) ->
                    headers.add(key, value.asString)
                }
            } catch (e: Exception) {
                // 忽略无效的自定义 headers
            }
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .headers(headers.build())
                .build()
            
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    trySend("Error: Network failure - ${e.message}")
                    close()
                }
                
                override fun onResponse(call: Call, response: Response) {
                    try {
                        if (!response.isSuccessful) {
                            val err = response.body?.string()?.take(500) ?: ""
                            trySend("Error: HTTP ${response.code} ${response.message} $err")
                            return
                        }
                        response.body?.source()?.let { source ->
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                if (line.startsWith("data: ")) {
                                    val data = line.substring(6)
                                    if (data == "[DONE]") break
                                    try {
                                        val json = JsonParser.parseString(data).asJsonObject
                                        val choices = json.getAsJsonArray("choices")
                                        if (choices != null && choices.size() > 0) {
                                            val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                            val content = delta?.get("content")?.takeIf { !it.isJsonNull }?.asString
                                            if (content != null) {
                                                trySend(content)
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // 解析错误，继续处理
                                    }
                                }
                            }
                        }
                    } catch (e: Exception) {
                        trySend("Error: ${e.message}")
                    } finally {
                        close()
                    }
                }
            })
            
            awaitClose { call.cancel() }
            
        } catch (e: Exception) {
            trySend("Error: ${e.message}")
            close()
        }
    }
    
    /**
     * Ollama 本地模型 - 流式请求
     */
    private fun sendOllamaStream(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>,
        systemPrompt: String? = null
    ): Flow<String> = callbackFlow {
        try {
            val url = "${settings.localModelUrl}/api/chat"

            val ollamaMessages = buildList {
                if (!systemPrompt.isNullOrBlank()) add(mapOf("role" to "system", "content" to systemPrompt))
                messages.forEach { msg ->
                    add(mapOf(
                        "role" to msg.role.name.lowercase(),
                        "content" to msg.content
                    ))
                }
            }
            
            val requestBody = mapOf(
                "model" to settings.localModelName,
                "messages" to ollamaMessages,
                "stream" to true
            )
            
            val request = Request.Builder()
                .url(url)
                .post(gson.toJson(requestBody).toRequestBody("application/json".toMediaType()))
                .build()
            
            val call = client.newCall(request)
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    trySend("Error: Ollama connection failed - ${e.message}")
                    close()
                }
                
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.body?.source()?.let { source ->
                            while (!source.exhausted()) {
                                val line = source.readUtf8Line() ?: break
                                try {
                                    val json = JsonParser.parseString(line).asJsonObject
                                    val message = json.getAsJsonObject("message")
                                    val content = message?.get("content")?.asString
                                    if (content != null) {
                                        trySend(content)
                                    }
                                } catch (e: Exception) {
                                    // Parse error, continue
                                }
                            }
                        }
                    } catch (e: Exception) {
                        trySend("Error: ${e.message}")
                    } finally {
                        close()
                    }
                }
            })
            
            awaitClose { call.cancel() }
            
        } catch (e: Exception) {
            trySend("Error: ${e.message}")
            close()
        }
    }
    
    /**
     * 构建请求消息列表（包含上下文，支持多模态）
     */
    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>,
        model: ModelConfig,
        systemPrompt: String? = null
    ): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()

        // 注入 Ask 模式系统人设（在上下文之前，作为主系统提示）
        if (!systemPrompt.isNullOrBlank()) {
            result.add(mapOf("role" to "system", "content" to systemPrompt))
        }

        // 添加系统提示（包含文本上下文）
        val textItems = contextItems.filter { !it.isImage }
        if (textItems.isNotEmpty()) {
            val contextText = textItems.joinToString("\n\n") { item ->
                "File: ${item.displayName}\n```\n${item.content}\n```"
            }
            result.add(mapOf(
                "role" to "system",
                "content" to "Context information:\n$contextText"
            ))
        }

        // 收集图片上下文（多模态）
        val imageItems = contextItems.filter { it.isImage && it.base64Data != null }
        
        // 添加历史消息
        messages.forEachIndexed { index, msg ->
            val isLastUser = index == messages.lastIndex && msg.role == MessageRole.USER
            if (isLastUser && imageItems.isNotEmpty() && model.supportsVision) {
                // 最后一条用户消息附带图片，构造多模态 content 数组
                val parts = mutableListOf<Map<String, Any>>()
                parts.add(mapOf("type" to "text", "text" to msg.content))
                imageItems.forEach { img ->
                    parts.add(mapOf(
                        "type" to "image_url",
                        "image_url" to mapOf("url" to (img.base64Data ?: ""))
                    ))
                }
                result.add(mapOf("role" to "user", "content" to parts))
            } else {
                result.add(mapOf(
                    "role" to msg.role.name.lowercase(),
                    "content" to msg.content
                ))
            }
        }
        
        return result
    }
    
    /**
     * 构建请求体（支持模板 + 额外字段注入，如 tools）
     */
    private fun buildRequestBody(
        messages: List<Map<String, Any>>,
        model: ModelConfig,
        extraBodyJson: String? = null
    ): String {
        var template = model.bodyTemplate.ifBlank { ModelConfig.DEFAULT_BODY_TEMPLATE }
        
        template = template.replace("{{model}}", model.modelName)
        val messagesJson = gson.toJson(messages)
        template = template.replace("{{messages}}", messagesJson)
        // {{tools}} 占位符：无则置为空数组，供 Phase1 function calling 使用
        template = template.replace("{{tools}}", extraBodyJson ?: "[]")

        // 若模板里没有 tools 占位符但传入了 extraBodyJson，则合并进对象
        if (extraBodyJson != null && !model.bodyTemplate.contains("{{tools}}")) {
            return mergeExtraBody(template, extraBodyJson)
        }
        return template
    }

    private fun mergeExtraBody(bodyJson: String, extraToolsJson: String): String {
        return try {
            val obj = JsonParser.parseString(bodyJson).asJsonObject
            obj.add("tools", JsonParser.parseString(extraToolsJson))
            gson.toJson(obj)
        } catch (e: Exception) {
            bodyJson
        }
    }
    
    /**
     * 非流式请求（用于不支持 SSE 的 API 或内部一次性调用）
     */
    suspend fun sendChatRequest(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>,
        model: ModelConfig = activeModel()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${model.baseUrl.trimEnd('/')}/chat/completions"
            val requestMessages = buildRequestMessages(messages, contextItems, model)
            // 非流式：强制 stream=false
            var body = buildRequestBody(requestMessages, model)
            body = body.replace("\"stream\": true", "\"stream\": false")
                       .replace("\"stream\":true", "\"stream\":false")

            val builder = Request.Builder()
                .url(url)
                .post(body.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
            if (model.apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer ${model.apiKey}")
            }
            try {
                val customHeaders = JsonParser.parseString(model.headersJson).asJsonObject
                customHeaders.entrySet().forEach { (key, value) ->
                    builder.addHeader(key, value.asString)
                }
            } catch (e: Exception) { }

            val response = client.newCall(builder.build()).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            
            val responseBody = response.body?.string() ?: ""
            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
            
            val choices = jsonResponse.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val message = choices[0].asJsonObject.getAsJsonObject("message")
                val content = message?.get("content")?.takeIf { !it.isJsonNull }?.asString
                if (content != null) {
                    return@withContext Result.success(content)
                }
            }
            
            Result.failure(IOException("Invalid response format"))
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Agent 专用：非流式原始补全，返回结构化 AssistantTurn（内容 + tool_calls）。
     * rawMessages 为已构造好的 OpenAI 消息数组（含 role/content/tool_calls/tool_call_id 等）。
     * toolsJson 非空时注入 tools 字段（function calling）。
     */
    suspend fun requestRawCompletion(
        rawMessages: List<Map<String, Any?>>,
        model: ModelConfig,
        toolsJson: String? = null,
        temperature: Double = model.temperature
    ): Result<AssistantTurn> = withContext(Dispatchers.IO) {
        try {
            val url = "${model.baseUrl.trimEnd('/')}/chat/completions"
            val bodyObj = JsonObject()
            bodyObj.addProperty("model", model.modelName)
            bodyObj.add("messages", gson.toJsonTree(rawMessages))
            bodyObj.addProperty("stream", false)
            bodyObj.addProperty("temperature", temperature)
            if (toolsJson != null) {
                try {
                    bodyObj.add("tools", JsonParser.parseString(toolsJson))
                    bodyObj.addProperty("tool_choice", "auto")
                } catch (e: Exception) { }
            }

            val builder = Request.Builder()
                .url(url)
                .post(gson.toJson(bodyObj).toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
            if (model.apiKey.isNotBlank()) builder.addHeader("Authorization", "Bearer ${model.apiKey}")
            try {
                JsonParser.parseString(model.headersJson).asJsonObject.entrySet().forEach { (k, v) ->
                    builder.addHeader(k, v.asString)
                }
            } catch (e: Exception) { }

            val response = client.newCall(builder.build()).execute()
            val responseBody = response.body?.string() ?: ""
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${responseBody.take(500)}"))
            }

            val json = JsonParser.parseString(responseBody).asJsonObject
            val choices = json.getAsJsonArray("choices")
                ?: return@withContext Result.failure(IOException("no choices in response"))
            if (choices.size() == 0) return@withContext Result.failure(IOException("empty choices"))

            val message = choices[0].asJsonObject.getAsJsonObject("message")
            val content = message?.get("content")?.takeIf { !it.isJsonNull }?.asString
            val toolCalls = mutableListOf<ToolCall>()
            message?.getAsJsonArray("tool_calls")?.forEach { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString
                    ?: java.util.UUID.randomUUID().toString()
                val fn = o.getAsJsonObject("function") ?: return@forEach
                val fname = fn.get("name")?.asString ?: return@forEach
                val fargs = fn.get("arguments")?.takeIf { !it.isJsonNull }?.asString ?: "{}"
                toolCalls.add(ToolCall(id, fname, fargs))
            }
            Result.success(AssistantTurn(content, toolCalls))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 一次性文本补全（供内联补全 / 诊断修复 / 测试生成 / 评审 / 提交信息等场景）。
     */
    suspend fun completeText(
        systemPrompt: String,
        userPrompt: String,
        model: ModelConfig,
        temperature: Double = 0.2
    ): Result<String> {
        val messages = listOf(
            mapOf("role" to "system", "content" to systemPrompt),
            mapOf("role" to "user", "content" to userPrompt)
        )
        return requestRawCompletion(messages, model, null, temperature)
            .mapCatching { it.content ?: throw IllegalStateException("empty completion") }
    }

    /**
     * 测试连接
     */
    suspend fun testConnection(model: ModelConfig = activeModel()): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = when (settings.getCurrentDriverMode()) {
                AIApplicationSettings.DriverMode.LOCAL_OLLAMA -> "${settings.localModelUrl}/api/tags"
                else -> "${model.baseUrl.trimEnd('/')}/models"
            }
            
            val builder = Request.Builder().url(url)
            if (model.apiKey.isNotBlank()) {
                builder.addHeader("Authorization", "Bearer ${model.apiKey}")
            }
            
            val response = client.newCall(builder.build()).execute()
            
            if (response.isSuccessful) {
                Result.success("Connection successful!")
            } else {
                Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
