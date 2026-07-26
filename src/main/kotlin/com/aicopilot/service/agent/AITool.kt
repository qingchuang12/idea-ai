package com.aicopilot.service.agent

import com.google.gson.JsonObject
import com.intellij.openapi.project.Project

/**
 * Agent 工具执行结果。
 */
data class ToolResult(
    val ok: Boolean,
    val output: String
) {
    companion object {
        fun ok(output: String) = ToolResult(true, output)
        fun error(message: String) = ToolResult(false, "ERROR: $message")
    }
}

/**
 * 工具执行上下文。
 * @param project 当前工程
 * @param confirm 危险操作确认回调（返回 true 表示允许执行）
 */
data class ToolContext(
    val project: Project,
    val confirm: (title: String, detail: String) -> Boolean = { _, _ -> true }
)

/**
 * Agent 工具抽象。所有底层能力（文件读写、终端、搜索、符号检索）统一实现该接口，
 * 由 AgentLoop 在两种协议（function calling / 文本协议）下统一驱动。
 */
interface AITool {
    /** 工具名（唯一，供模型调用） */
    val name: String

    /** 工具用途描述（提供给模型） */
    val description: String

    /**
     * JSON Schema 形式的参数定义（OpenAI function parameters 规范）。
     * 例：mapOf("type" to "object", "properties" to mapOf(...), "required" to listOf(...))
     */
    val parameters: Map<String, Any>

    /** 是否需要用户确认后才能执行（写文件 / 终端命令等） */
    val requiresConfirmation: Boolean get() = false

    /** 执行工具 */
    suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult

    /** 生成 OpenAI function calling 规范的工具定义 */
    fun toFunctionSpec(): Map<String, Any> = mapOf(
        "type" to "function",
        "function" to mapOf(
            "name" to name,
            "description" to description,
            "parameters" to parameters
        )
    )
}

/** 参数 schema 构造辅助 */
object ToolSchema {
    fun obj(vararg props: Pair<String, Map<String, Any>>, required: List<String> = emptyList()): Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to props.toMap(),
            "required" to required
        )

    fun string(desc: String): Map<String, Any> = mapOf("type" to "string", "description" to desc)
    fun integer(desc: String): Map<String, Any> = mapOf("type" to "integer", "description" to desc)
    fun bool(desc: String): Map<String, Any> = mapOf("type" to "boolean", "description" to desc)
}
