package com.aicopilot.model

/**
 * 上下文项 - 表示添加到会话中的文件或文本片段
 */
data class ContextItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ContextType,
    val content: String,
    val displayName: String,
    val filePath: String? = null,
    val characterCount: Int = content.length,
    val isImage: Boolean = false,
    val base64Data: String? = null  // 用于图片的 Base64 数据
) {
    fun getPreview(): String {
        return if (content.length > 100) {
            content.substring(0, 100) + "..."
        } else {
            content
        }
    }
}

enum class ContextType {
    SELECTION,      // 编辑器选区
    FILE,           // 文件内容
    IMAGE,          // 图片文件
    CUSTOM_TEXT     // 自定义文本
}

/**
 * 聊天消息模型
 */
data class ChatMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val contextItems: List<ContextItem> = emptyList()
)

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

/**
 * API 请求/响应模型
 */
data class ApiRequest(
    val model: String,
    val messages: List<Message>,
    val stream: Boolean = true,
    val maxTokens: Int? = null,
    val temperature: Double = 0.7
) {
    data class Message(
        val role: String,
        val content: Any  // String 或 List<ContentPart> 用于多模态
    )
    
    data class ContentPart(
        val type: String,  // "text" or "image_url"
        val text: String? = null,
        val imageUrl: ImageUrl? = null
    ) {
        data class ImageUrl(
            val url: String  // Base64 或 URL
        )
    }
}

data class ApiResponse(
    val id: String?,
    val choices: List<Choice>?,
    val error: Error?
) {
    data class Choice(
        val message: Message?,
        val delta: Delta?,
        val finishReason: String?
    ) {
        data class Message(
            val role: String,
            val content: String
        )
        
        data class Delta(
            val role: String?,
            val content: String?
        )
    }
    
    data class Error(
        val message: String,
        val type: String,
        val code: String?
    )
}

/**
 * Agent 工具调用（由模型请求发起）。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val argumentsJson: String
)

/**
 * 一次助手回合的结构化结果（内容 + 工具调用）。
 */
data class AssistantTurn(
    val content: String?,
    val toolCalls: List<ToolCall>
)

/**
 * Agent 执行过程中的事件，用于 UI 可视化展示。
 */
sealed class AgentEvent {
    data class Thinking(val text: String) : AgentEvent()
    data class AssistantMessage(val text: String) : AgentEvent()
    data class ToolInvoked(val call: ToolCall) : AgentEvent()
    data class ToolFinished(val call: ToolCall, val ok: Boolean, val output: String) : AgentEvent()
    data class Error(val message: String) : AgentEvent()
    data class Finished(val finalText: String) : AgentEvent()
}
