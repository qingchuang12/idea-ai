package com.aicopilot.service

import com.aicopilot.model.*
import com.aicopilot.settings.AIApplicationSettings
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * AI 聊天服务 - 支持多驱动模式
 */
class ChatService {
    
    private val settings = AIApplicationSettings.getInstance()
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(settings.requestTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        .build()
    
    /**
     * 发送聊天请求（流式）
     */
    suspend fun sendChatStream(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>
    ): Flow<String> = flow {
        when (settings.getCurrentDriverMode()) {
            AIApplicationSettings.DriverMode.NATIVE_API -> {
                emitAll(sendNativeApiStream(messages, contextItems))
            }
            AIApplicationSettings.DriverMode.LOCAL_OLLAMA -> {
                emitAll(sendOllamaStream(messages, contextItems))
            }
            AIApplicationSettings.DriverMode.WEBVIEW -> {
                // WebView 模式下不通过 HTTP 发送，由前端处理
                emit("[WebView Mode] Please use the embedded browser for chat.")
            }
        }
    }.flowOn(Dispatchers.IO)
    
    /**
     * 原生 API 模式 - 流式请求
     */
    private suspend fun sendNativeApiStream(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>
    ): Flow<String> = flow {
        try {
            val url = "${settings.baseUrl}/chat/completions"
            
            // 构建请求体
            val requestMessages = buildRequestMessages(messages, contextItems)
            val requestBody = buildRequestBody(requestMessages)
            
            // 构建 Headers
            val headers = Headers.Builder()
                .add("Content-Type", "application/json")
                .add("Authorization", "Bearer ${settings.apiKey}")
            
            // 添加自定义 Headers
            try {
                val customHeaders = JsonParser.parseString(settings.customHeaders).asJsonObject
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
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // 在流中发送错误信息
                    try {
                        emit("Error: Network failure - ${e.message}")
                    } catch (ex: Exception) {
                        // Ignore
                    }
                }
                
                override fun onResponse(call: Call, response: Response) {
                    response.body?.source()?.let { source ->
                        var buffer = ""
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            if (line.startsWith("data: ")) {
                                val data = line.substring(6)
                                if (data == "[DONE]") {
                                    break
                                }
                                try {
                                    val json = JsonParser.parseString(data).asJsonObject
                                    val choices = json.getAsJsonArray("choices")
                                    if (choices != null && choices.size() > 0) {
                                        val delta = choices[0].asJsonObject.getAsJsonObject("delta")
                                        val content = delta?.get("content")?.asString
                                        if (content != null) {
                                            try {
                                                emit(content)
                                            } catch (e: Exception) {
                                                // Ignore emission errors
                                            }
                                        }
                                    }
                                } catch (e: Exception) {
                                    // 解析错误，继续处理
                                }
                            }
                        }
                    }
                }
            })
            
            // 等待请求完成（简化实现）
            kotlinx.coroutines.delay(settings.requestTimeoutSeconds.toLong() * 1000)
            
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }
    
    /**
     * Ollama 本地模型 - 流式请求
     */
    private suspend fun sendOllamaStream(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>
    ): Flow<String> = flow {
        try {
            val url = "${settings.localModelUrl}/api/chat"
            
            val ollamaMessages = messages.map { msg ->
                mapOf(
                    "role" to msg.role.name.lowercase(),
                    "content" to msg.content
                )
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
            
            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    try {
                        emit("Error: Ollama connection failed - ${e.message}")
                    } catch (ex: Exception) {
                        // Ignore
                    }
                }
                
                override fun onResponse(call: Call, response: Response) {
                    response.body?.source()?.let { source ->
                        while (!source.exhausted()) {
                            val line = source.readUtf8Line() ?: break
                            try {
                                val json = JsonParser.parseString(line).asJsonObject
                                val message = json.getAsJsonObject("message")
                                val content = message?.get("content")?.asString
                                if (content != null) {
                                    try {
                                        emit(content)
                                    } catch (e: Exception) {
                                        // Ignore
                                    }
                                }
                            } catch (e: Exception) {
                                // Parse error, continue
                            }
                        }
                    }
                }
            })
            
            kotlinx.coroutines.delay(settings.requestTimeoutSeconds.toLong() * 1000)
            
        } catch (e: Exception) {
            emit("Error: ${e.message}")
        }
    }
    
    /**
     * 构建请求消息列表（包含上下文）
     */
    private fun buildRequestMessages(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>
    ): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        
        // 添加系统提示（包含上下文）
        if (contextItems.isNotEmpty()) {
            val contextText = contextItems.joinToString("\n\n") { item ->
                if (item.isImage) {
                    "[Image: ${item.displayName}]"
                } else {
                    "File: ${item.displayName}\n```${item.content}```"
                }
            }
            result.add(mapOf(
                "role" to "system",
                "content" to "Context information:\n$contextText"
            ))
        }
        
        // 添加历史消息
        messages.forEach { msg ->
            result.add(mapOf(
                "role" to msg.role.name.lowercase(),
                "content" to msg.content
            ))
        }
        
        return result
    }
    
    /**
     * 构建请求体（支持模板）
     */
    private fun buildRequestBody(messages: List<Map<String, Any>>): String {
        var template = settings.requestBodyTemplate
        
        // 替换模板变量
        template = template.replace("{{model}}", settings.getEffectiveModelName())
        
        // 替换 messages
        val messagesJson = gson.toJson(messages)
        template = template.replace("{{messages}}", messagesJson)
        
        return template
    }
    
    /**
     * 非流式请求（用于不支持 SSE 的 API）
     */
    suspend fun sendChatRequest(
        messages: List<ChatMessage>,
        contextItems: List<ContextItem>
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "${settings.baseUrl}/chat/completions"
            val requestMessages = buildRequestMessages(messages, contextItems)
            val requestBody = buildRequestBody(requestMessages)
            
            val request = Request.Builder()
                .url(url)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .build()
            
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("HTTP ${response.code}: ${response.message}"))
            }
            
            val responseBody = response.body?.string() ?: ""
            val jsonResponse = JsonParser.parseString(responseBody).asJsonObject
            
            val choices = jsonResponse.getAsJsonArray("choices")
            if (choices != null && choices.size() > 0) {
                val message = choices[0].asJsonObject.getAsJsonObject("message")
                val content = message?.get("content")?.asString
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
     * 测试连接
     */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = when (settings.getCurrentDriverMode()) {
                AIApplicationSettings.DriverMode.LOCAL_OLLAMA -> "${settings.localModelUrl}/api/tags"
                else -> "${settings.baseUrl}/models"
            }
            
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${settings.apiKey}")
                .build()
            
            val response = client.newCall(request).execute()
            
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
