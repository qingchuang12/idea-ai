package com.aicopilot.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * AI Copilot 应用级设置
 * 支持多驱动模式：原生 API、WebView 嵌入、本地 Ollama
 */
@State(
    name = "com.aicopilot.settings.AIApplicationSettings",
    storages = [Storage("ai-copilot-settings.xml")]
)
class AIApplicationSettings : PersistentStateComponent<AIApplicationSettings> {
    
    // 驱动模式枚举
    enum class DriverMode {
        NATIVE_API,      // 原生 API 模式
        WEBVIEW,         // WebView 嵌入式模式
        LOCAL_OLLAMA     // 本地 Ollama/LM Studio
    }
    
    // 当前选择的驱动模式
    var driverMode: String = DriverMode.NATIVE_API.name
    
    // ========== 原生 API 模式配置 ==========
    var baseUrl: String = "https://api.openai.com/v1"
    var apiKey: String = ""
    var modelName: String = "gpt-3.5-turbo"
    var customHeaders: String = "{}"  // JSON 格式的自定义 headers
    var requestBodyTemplate: String = """{
    "model": "{{model}}",
    "messages": {{messages}},
    "stream": true
}"""
    
    // ========== WebView 模式配置 ==========
    var webViewUrl: String = "https://chat.openai.com"
    var enableJcefBridge: Boolean = true
    
    // ========== 本地模型配置 ==========
    var localModelUrl: String = "http://localhost:11434"
    var localModelName: String = "llama2"
    
    // ========== 通用配置 ==========
    var maxContextLength: Int = 8000  // 最大上下文长度（token 数）
    var requestTimeoutSeconds: Int = 60
    var enableStreaming: Boolean = true
    
    override fun getState(): AIApplicationSettings = this
    
    override fun loadState(state: AIApplicationSettings) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        fun getInstance(): AIApplicationSettings = 
            ApplicationManager.getApplication().getService(AIApplicationSettings::class.java)
    }
    
    fun getCurrentDriverMode(): DriverMode = 
        try {
            DriverMode.valueOf(driverMode)
        } catch (e: Exception) {
            DriverMode.NATIVE_API
        }
    
    fun getEffectiveBaseUrl(): String = when (getCurrentDriverMode()) {
        DriverMode.NATIVE_API -> baseUrl
        DriverMode.WEBVIEW -> webViewUrl
        DriverMode.LOCAL_OLLAMA -> localModelUrl
    }
    
    fun getEffectiveModelName(): String = when (getCurrentDriverMode()) {
        DriverMode.LOCAL_OLLAMA -> localModelName
        else -> modelName
    }
}
