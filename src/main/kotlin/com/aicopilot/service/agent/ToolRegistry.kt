package com.aicopilot.service.agent

import com.intellij.openapi.application.ApplicationManager

/**
 * 工具注册表（应用级服务）。管理所有可供 Agent 调用的工具。
 * 工具为无状态单例，运行时通过 ToolContext 传入工程等信息。
 */
class ToolRegistry {

    private val tools = linkedMapOf<String, AITool>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        register(FileReadTool())
        register(FileWriteTool())
        register(TerminalTool())
        register(ProjectSearchTool())
        register(SymbolLookupTool())
        // Phase 5: 深度工程理解
        register(CodeIndexSearchTool())
        register(KnowledgeQueryTool())
        register(MemorySaveTool())
        register(MemoryRecallTool())
        // Phase 6: 开放生态
        register(RulesQueryTool())
        register(SkillTool())
        register(McpListTool())
        register(McpCallTool())
    }

    fun register(tool: AITool) {
        tools[tool.name] = tool
    }

    fun getTool(name: String): AITool? = tools[name]

    fun getAllTools(): List<AITool> = tools.values.toList()

    /** 生成 OpenAI function calling 规范的 tools 数组 */
    fun toFunctionSpecs(): List<Map<String, Any>> = tools.values.map { it.toFunctionSpec() }

    /** 生成文本协议下的工具说明（供 system prompt 注入） */
    fun toTextProtocolDescription(): String = buildString {
        appendLine("你可以调用以下工具（每次仅调用一个）。")
        appendLine("如需调用工具，请只输出一个如下格式的代码块，不要输出其它内容：")
        appendLine("```tool")
        appendLine("""{"tool": "工具名", "args": { ... }}""")
        appendLine("```")
        appendLine("工具执行结果会以 [TOOL_RESULT] 的形式回传给你，然后你继续。")
        appendLine("当任务完成、无需再调用工具时，直接输出最终自然语言回答（不要再输出 tool 代码块）。")
        appendLine()
        appendLine("可用工具列表：")
        tools.values.forEach { t ->
            appendLine("- ${t.name}: ${t.description}")
            appendLine("  参数: ${(t.parameters["properties"] as? Map<*, *>)?.keys?.joinToString(", ") ?: ""}")
        }
    }

    companion object {
        fun getInstance(): ToolRegistry =
            ApplicationManager.getApplication().getService(ToolRegistry::class.java)
    }
}
