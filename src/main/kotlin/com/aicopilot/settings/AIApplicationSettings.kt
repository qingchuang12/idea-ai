package com.aicopilot.settings

import com.aicopilot.model.ModelConfig
import com.aicopilot.model.ModelProtocol
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/**
 * AI Copilot 应用级设置
 * 原生 API（多模型 BYOK）与本地 Ollama 两种驱动模式，以及多模型 BYOK 管理。
 */
@State(
    name = "com.aicopilot.settings.AIApplicationSettings",
    storages = [Storage("ai-copilot-settings.xml")]
)
class AIApplicationSettings : PersistentStateComponent<AIApplicationSettings> {
    
    // 驱动模式枚举
    enum class DriverMode {
        NATIVE_API,      // 原生 API 模式（默认，多模型 BYOK）
        LOCAL_OLLAMA     // 本地 Ollama/LM Studio
    }
    
    // 当前选择的驱动模式
    var driverMode: String = DriverMode.NATIVE_API.name

    // ========== 多模型 BYOK 管理 ==========
    /** UI 管理的模型列表（持久化） */
    @XCollection(style = XCollection.Style.v2)
    var models: MutableList<ModelConfig> = mutableListOf()

    /** 当前激活模型 id */
    var activeModelId: String = ""
    
    // ========== 原生 API 模式配置（Legacy，用于迁移到 models 列表） ==========
    var baseUrl: String = "https://api.openai.com/v1"
    var apiKey: String = ""
    var modelName: String = "gpt-3.5-turbo"
    var customHeaders: String = "{}"  // JSON 格式的自定义 headers
    var requestBodyTemplate: String = """{
    "model": "{{model}}",
    "messages": {{messages}},
    "stream": true
}"""
    
    // ========== 本地模型配置 ==========
    var localModelUrl: String = "http://localhost:11434"
    var localModelName: String = "llama2"
    
    // ========== 通用配置 ==========
    var maxContextLength: Int = 8000  // 最大上下文长度（token 数）
    var requestTimeoutSeconds: Int = 60
    var enableStreaming: Boolean = true

    // ========== 功能开关（供各 Phase 使用） ==========
    var enableInlineCompletion: Boolean = true
    var enableAgent: Boolean = true
    /** Agent 执行文件写入/终端命令前是否需要用户确认 */
    var agentRequireConfirmation: Boolean = true
    var enableRules: Boolean = true
    var enableMcp: Boolean = false
    var enableMemory: Boolean = true

    // ========== 本轮新增开关 ==========
    /** 行间预测（NES - Next Edit Suggestion） */
    var enableNes: Boolean = true
    /** 智能问答（Ask 模式）主开关 */
    var enableSmartChat: Boolean = true
    /** 沟通窗口自动加载当前打开文件到上下文 */
    var autoLoadOpenFile: Boolean = false
    /** AI 回复语言：Auto / 中文 / English */
    var replyLanguage: String = "Auto"
    /** HTTP 代理（供 OkHttp 使用） */
    var httpProxyHost: String = ""
    var httpProxyPort: Int = 0
    /** 启动时为空模型列表自动加载内置厂商预设 */
    var autoUpdateBuiltinModels: Boolean = true
    /** 内置厂商模型是否已播种（避免重复） */
    var builtinSeeded: Boolean = false

    /** 标记 legacy 单模型是否已迁移到 models 列表 */
    var legacyMigrated: Boolean = false
    
    override fun getState(): AIApplicationSettings = this
    
    override fun loadState(state: AIApplicationSettings) {
        XmlSerializerUtil.copyBean(state, this)
        migrateLegacyIfNeeded()
    }

    /**
     * 将旧的单模型配置迁移为 models 列表中的一项，保证升级平滑。
     */
    fun migrateLegacyIfNeeded() {
        if (legacyMigrated) return
        if (models.isEmpty()) {
            val migrated = ModelConfig(
                alias = "Default (migrated)",
                baseUrl = baseUrl,
                apiKey = apiKey,
                modelName = modelName,
                headersJson = customHeaders.ifBlank { "{}" },
                bodyTemplate = requestBodyTemplate,
                protocol = ModelProtocol.OPENAI_COMPAT.name,
                enabled = true
            )
            models.add(migrated)
            if (activeModelId.isBlank()) activeModelId = migrated.id
        }
        legacyMigrated = true
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
        DriverMode.LOCAL_OLLAMA -> localModelUrl
    }
    
    fun getEffectiveModelName(): String = when (getCurrentDriverMode()) {
        DriverMode.LOCAL_OLLAMA -> localModelName
        else -> modelName
    }
}
