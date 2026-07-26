package com.aicopilot.service.agent

import com.aicopilot.model.AgentEvent
import com.aicopilot.model.ContextItem
import com.aicopilot.model.ModelConfig
import com.aicopilot.model.ModelProtocol
import com.aicopilot.model.ToolCall
import com.aicopilot.service.ChatService
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 双协议 Agent 执行循环。
 *
 * 统一驱动两类模型：
 *  - 支持 function calling 的模型：注入 tools 字段，解析 tool_calls
 *  - 仅普通 chat 的模型：在 system 注入文本协议约定，解析助手文本中的工具调用片段
 *
 * 流程：LLM → 解析工具调用 → 权限确认 → 执行 → 回流结果 → 循环，直至无工具调用或达到上限。
 */
class AgentLoop(
    private val chatService: ChatService = ChatService(),
    private val toolRegistry: ToolRegistry = ToolRegistry.getInstance()
) {

    private val gson = Gson()

    private val toolBlockRegex = Regex("```tool\\s*(\\{[\\s\\S]*?})\\s*```", RegexOption.IGNORE_CASE)

    suspend fun run(
        userTask: String,
        systemPreamble: String,
        contextItems: List<ContextItem>,
        model: ModelConfig,
        ctx: ToolContext,
        maxIterations: Int = 8,
        onEvent: (AgentEvent) -> Unit
    ) {
        val useFunctionCalling =
            model.supportsFunctionCalling && model.protocolEnum() == ModelProtocol.FUNCTION_CALLING

        val messages = mutableListOf<Map<String, Any?>>()
        messages.add(mapOf("role" to "system", "content" to buildSystemContent(systemPreamble, contextItems, useFunctionCalling)))
        messages.add(mapOf("role" to "user", "content" to userTask))

        val toolsJson = if (useFunctionCalling) gson.toJson(toolRegistry.toFunctionSpecs()) else null

        var iteration = 0
        while (iteration < maxIterations) {
            iteration++
            onEvent(AgentEvent.Thinking("Iteration $iteration ..."))

            val turn = chatService.requestRawCompletion(messages, model, toolsJson).getOrElse {
                onEvent(AgentEvent.Error(it.message ?: "request failed"))
                return
            }

            // ===== Function Calling 协议 =====
            if (useFunctionCalling && turn.toolCalls.isNotEmpty()) {
                messages.add(assistantToolCallMessage(turn))
                for (call in turn.toolCalls) {
                    onEvent(AgentEvent.ToolInvoked(call))
                    val result = executeTool(call, ctx)
                    onEvent(AgentEvent.ToolFinished(call, result.ok, result.output))
                    messages.add(
                        mapOf(
                            "role" to "tool",
                            "tool_call_id" to call.id,
                            "content" to result.output
                        )
                    )
                }
                continue
            }

            val content = turn.content ?: ""

            // ===== 文本协议 =====
            if (!useFunctionCalling) {
                val call = parseTextToolCall(content)
                if (call != null) {
                    val visible = stripToolBlock(content)
                    if (visible.isNotBlank()) onEvent(AgentEvent.AssistantMessage(visible))
                    messages.add(mapOf("role" to "assistant", "content" to content))
                    onEvent(AgentEvent.ToolInvoked(call))
                    val result = executeTool(call, ctx)
                    onEvent(AgentEvent.ToolFinished(call, result.ok, result.output))
                    messages.add(
                        mapOf(
                            "role" to "user",
                            "content" to "[TOOL_RESULT] ${call.name}:\n${result.output}"
                        )
                    )
                    continue
                }
            }

            // ===== 无工具调用 → 结束 =====
            onEvent(AgentEvent.Finished(content))
            return
        }
        onEvent(AgentEvent.Error("Reached max iterations ($maxIterations)."))
    }

    private fun buildSystemContent(
        preamble: String,
        contextItems: List<ContextItem>,
        useFunctionCalling: Boolean
    ): String = buildString {
        append(preamble)
        if (!useFunctionCalling) {
            append("\n\n")
            append(toolRegistry.toTextProtocolDescription())
        }
        val ctxText = contextItems.filter { !it.isImage }
            .joinToString("\n\n") { "File: ${it.displayName}\n```\n${it.content}\n```" }
        if (ctxText.isNotBlank()) {
            append("\n\n# Context\n")
            append(ctxText)
        }
    }

    private fun assistantToolCallMessage(turn: com.aicopilot.model.AssistantTurn): Map<String, Any?> =
        mapOf(
            "role" to "assistant",
            "content" to (turn.content ?: ""),
            "tool_calls" to turn.toolCalls.map {
                mapOf(
                    "id" to it.id,
                    "type" to "function",
                    "function" to mapOf("name" to it.name, "arguments" to it.argumentsJson)
                )
            }
        )

    private suspend fun executeTool(call: ToolCall, ctx: ToolContext): ToolResult {
        val tool = toolRegistry.getTool(call.name)
            ?: return ToolResult.error("unknown tool: ${call.name}")
        val args = try {
            JsonParser.parseString(call.argumentsJson).asJsonObject
        } catch (e: Exception) {
            JsonObject()
        }
        if (tool.requiresConfirmation) {
            val allowed = ctx.confirm("Execute tool: ${tool.name}", "arguments: ${call.argumentsJson}")
            if (!allowed) return ToolResult.error("user rejected execution")
        }
        return try {
            tool.execute(args, ctx)
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "tool execution failed")
        }
    }

    private fun parseTextToolCall(content: String): ToolCall? {
        val match = toolBlockRegex.find(content)
        val jsonStr = match?.groupValues?.getOrNull(1) ?: run {
            // 兜底：内容整体是否为带 "tool" 键的 JSON
            val trimmed = content.trim()
            if (trimmed.startsWith("{") && trimmed.contains("\"tool\"")) trimmed else return null
        }
        return try {
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val name = obj.get("tool")?.asString ?: return null
            val argsJson = if (obj.has("args") && obj.get("args").isJsonObject)
                obj.getAsJsonObject("args").toString() else "{}"
            ToolCall(java.util.UUID.randomUUID().toString(), name, argsJson)
        } catch (e: Exception) {
            null
        }
    }

    private fun stripToolBlock(content: String): String =
        content.replace(toolBlockRegex, "").trim()
}
