package com.aicopilot.settings

import com.aicopilot.model.ModelConfig
import com.aicopilot.model.ModelProvider
import com.aicopilot.model.ModelProtocol
import com.aicopilot.model.ModelSource
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

/**
 * 模型注册表（应用级服务）。
 *
 * 合并来源：
 *  - UI 管理的模型（持久化于 [AIApplicationSettings.models]）
 *  - 声明式 models.json（项目级 / 用户级，运行时加载，不持久化）
 *
 * 负责统一的模型选择路由，供 ChatService / Agent 使用。
 */
class ModelRegistry {

    private val settings get() = AIApplicationSettings.getInstance()

    /** 来自 models.json 的运行期模型（不持久化） */
    @Volatile
    private var jsonModels: List<ModelConfig> = emptyList()

    /** 在工程加载/工具窗口初始化时刷新 JSON 模型 */
    fun refreshJsonModels(project: Project?) {
        jsonModels = try {
            ModelsJsonLoader.load(project)
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 全部模型：UI 模型 + JSON 模型 */
    fun getAllModels(): List<ModelConfig> = settings.models + jsonModels

    /** 仅启用的模型 */
    fun getEnabledModels(): List<ModelConfig> = getAllModels().filter { it.enabled }

    /** 当前激活模型（无则取第一个启用项） */
    fun getActiveModel(): ModelConfig? {
        val id = settings.activeModelId
        return getAllModels().firstOrNull { it.id == id && it.enabled }
            ?: getEnabledModels().firstOrNull()
    }

    fun setActiveModel(id: String) {
        settings.activeModelId = id
    }

    fun findById(id: String): ModelConfig? = getAllModels().firstOrNull { it.id == id }

    // ============ UI 模型 CRUD（仅作用于 settings.models） ============

    fun addModel(model: ModelConfig) {
        model.source = ModelSource.UI.name
        settings.models.add(model)
        if (settings.activeModelId.isBlank()) {
            settings.activeModelId = model.id
        }
    }

    fun updateModel(model: ModelConfig): Boolean {
        val idx = settings.models.indexOfFirst { it.id == model.id }
        if (idx < 0) return false
        settings.models[idx] = model
        return true
    }

    fun removeModel(id: String): Boolean {
        val removed = settings.models.removeAll { it.id == id }
        if (settings.activeModelId == id) {
            settings.activeModelId = getEnabledModels().firstOrNull()?.id ?: ""
        }
        return removed
    }

    fun setEnabled(id: String, enabled: Boolean) {
        settings.models.firstOrNull { it.id == id }?.enabled = enabled
    }

    /**
     * 内置厂商模型自动加载（"厂商模型自动加载，并提供切换"）。
     *
     * 当设置允许且当前没有任何模型配置时，将一组主流厂商预设（OpenAI / DeepSeek / Kimi /
     * 智谱 GLM / 通义千问 / 本地 Ollama 等）以 [ModelSource.BUILTIN] 来源播种进 UI 模型列表，
     * 用户只需在设置里填入对应 API Key 即可在聊天/补全/智能体中一键切换。
     * 若允许自动更新，则每次调用会补齐缺失的预设（已删除的不会被复活）。
     */
    fun seedBuiltinModels() {
        if (!settings.autoUpdateBuiltinModels) return
        val presets = builtinPresets()
        // 已存在的（按稳定 id）跳过
        val existing = settings.models.map { it.id }.toSet()
        val toAdd = presets.filter { it.id !in existing }
        if (toAdd.isEmpty()) return
        settings.models.addAll(toAdd)
        if (settings.activeModelId.isBlank()) {
            settings.activeModelId = toAdd.first().id
        }
    }

    /** 主流厂商预设（稳定 id 以 "builtin:" 前缀，避免重复加载）。 */
    private fun builtinPresets(): List<ModelConfig> = listOf(
        builtin("gpt-4o", "OpenAI GPT-4o", ModelProvider.OPENAI, "gpt-4o", true, true),
        builtin("gpt-4o-mini", "OpenAI GPT-4o-mini", ModelProvider.OPENAI, "gpt-4o-mini", true, false),
        builtin("deepseek-chat", "DeepSeek Chat", ModelProvider.DEEPSEEK, "deepseek-chat", true, false),
        builtin("moonshot-v1-8k", "Moonshot Kimi", ModelProvider.MOONSHOT, "moonshot-v1-8k", true, false),
        builtin("glm-4", "Zhipu GLM-4", ModelProvider.ZHIPU, "glm-4", true, false),
        builtin("qwen-max", "DashScope Qwen-Max", ModelProvider.DASHSCOPE, "qwen-max", true, false),
        builtin("claude", "Anthropic Claude", ModelProvider.ANTHROPIC, "claude-3-5-sonnet-latest", true, true),
        builtin("ollama", "Ollama (Local)", ModelProvider.OLLAMA, "llama2", false, false),
    )

    private fun builtin(
        modelName: String,
        alias: String,
        provider: ModelProvider,
        apiModel: String,
        fc: Boolean,
        vision: Boolean
    ): ModelConfig = ModelConfig(
        id = "builtin:$modelName",
        alias = alias,
        provider = provider.name,
        baseUrl = provider.defaultBaseUrl,
        apiKey = "",
        modelName = apiModel,
        headersJson = "{}",
        bodyTemplate = ModelConfig.DEFAULT_BODY_TEMPLATE,
        protocol = if (fc) ModelProtocol.FUNCTION_CALLING.name else ModelProtocol.OPENAI_COMPAT.name,
        supportsFunctionCalling = fc,
        supportsVision = vision,
        enabled = true,
        source = ModelSource.BUILTIN.name,
        temperature = 0.7
    )

    companion object {
        fun getInstance(): ModelRegistry =
            ApplicationManager.getApplication().getService(ModelRegistry::class.java)
    }
}
