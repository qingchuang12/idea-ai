package com.aicopilot.model

/**
 * 单个模型配置（BYOK - Bring Your Own Key）
 *
 * 兼容 OpenAI 接口协议及主流国产模型厂商，可通过自定义 baseUrl / headers 接入。
 * 该类需可被 IntelliJ XmlSerializer 序列化，因此全部字段为 var 且带默认值。
 */
data class ModelConfig(
    var id: String = java.util.UUID.randomUUID().toString(),
    var alias: String = "New Model",
    var provider: String = ModelProvider.OPENAI.name,
    var baseUrl: String = "https://api.openai.com/v1",
    var apiKey: String = "",
    var modelName: String = "gpt-4o-mini",
    /** JSON 格式的自定义 headers，用于鉴权等 */
    var headersJson: String = "{}",
    /** 请求体模板，支持 {{model}} / {{messages}} / {{tools}} 占位符 */
    var bodyTemplate: String = DEFAULT_BODY_TEMPLATE,
    /** 工具调用协议策略 */
    var protocol: String = ModelProtocol.OPENAI_COMPAT.name,
    /** 是否原生支持 function calling（决定 Agent 双协议策略） */
    var supportsFunctionCalling: Boolean = false,
    /** 是否支持多模态视觉输入 */
    var supportsVision: Boolean = false,
    /** 是否启用（禁用后不参与选择） */
    var enabled: Boolean = true,
    /** 配置来源：UI / PROJECT_JSON / USER_JSON / BUILTIN */
    var source: String = ModelSource.UI.name,
    /** 温度等采样参数（可选） */
    var temperature: Double = 0.7,
    /** 上下文最大 token（用于裁剪估算） */
    var maxContextTokens: Int = 8000
) {
    fun protocolEnum(): ModelProtocol =
        try { ModelProtocol.valueOf(protocol) } catch (e: Exception) { ModelProtocol.OPENAI_COMPAT }

    fun sourceEnum(): ModelSource =
        try { ModelSource.valueOf(source) } catch (e: Exception) { ModelSource.UI }

    /** 是否可编辑（UI 与内置厂商预设可编辑 API Key 等；JSON 声明式配置只读） */
    fun isEditable(): Boolean = sourceEnum() in setOf(ModelSource.UI, ModelSource.BUILTIN)

    /** 供 UI 下拉展示的名称 */
    fun displayLabel(): String {
        val suffix = when (sourceEnum()) {
            ModelSource.PROJECT_JSON -> " (project)"
            ModelSource.USER_JSON -> " (user)"
            ModelSource.BUILTIN -> " (builtin)"
            else -> ""
        }
        val flags = buildString {
            if (supportsFunctionCalling) append(" ⚙")
            if (supportsVision) append(" 👁")
        }
        return "$alias$suffix$flags"
    }

    companion object {
        const val DEFAULT_BODY_TEMPLATE = """{
  "model": "{{model}}",
  "messages": {{messages}},
  "stream": true
}"""
    }
}

/**
 * 工具调用协议策略。
 * - OPENAI_COMPAT：普通 chat completions，无工具调用能力
 * - FUNCTION_CALLING：请求体注入原生 tools 字段，解析 tool_calls
 * - TEXT_PROTOCOL：在 system 中注入约定，解析助手文本中的工具调用片段
 */
enum class ModelProtocol {
    OPENAI_COMPAT,
    FUNCTION_CALLING,
    TEXT_PROTOCOL
}

/** 配置来源 */
enum class ModelSource {
    UI,
    PROJECT_JSON,
    USER_JSON,
    BUILTIN
}

/** 常见模型厂商预设（用于快速填充默认 baseUrl） */
enum class ModelProvider(val displayName: String, val defaultBaseUrl: String) {
    OPENAI("OpenAI", "https://api.openai.com/v1"),
    AZURE_OPENAI("Azure OpenAI", "https://your-resource.openai.azure.com/openai/deployments/your-deployment"),
    ANTHROPIC("Anthropic", "https://api.anthropic.com/v1"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1"),
    MOONSHOT("Moonshot (Kimi)", "https://api.moonshot.cn/v1"),
    ZHIPU("Zhipu (GLM)", "https://open.bigmodel.cn/api/paas/v4"),
    DASHSCOPE("Aliyun DashScope (Qwen)", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
    OLLAMA("Ollama (Local)", "http://localhost:11434/v1"),
    CUSTOM("Custom", "")
}
