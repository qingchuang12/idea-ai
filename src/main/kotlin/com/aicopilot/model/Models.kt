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
