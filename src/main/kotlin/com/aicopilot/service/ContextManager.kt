package com.aicopilot.service

import com.aicopilot.model.ContextItem
import com.aicopilot.model.ContextType
import com.aicopilot.settings.AIApplicationSettings
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * 上下文管理服务 - 处理文件和选区的添加
 */
class ContextManager(private val project: Project) {
    
    private val settings = AIApplicationSettings.getInstance()
    private val logger = Logger.getInstance(ContextManager::class.java)
    
    // 当前会话的上下文列表
    private val contextItems = mutableListOf<ContextItem>()
    
    /**
     * 获取所有上下文项
     */
    fun getContextItems(): List<ContextItem> = contextItems.toList()
    
    /**
     * 添加编辑器选区到上下文
     */
    fun addSelection(text: String, description: String = "Selected Text"): ContextItem {
        val truncatedText = truncateIfNeeded(text)
        val item = ContextItem(
            type = ContextType.SELECTION,
            content = truncatedText,
            displayName = description,
            characterCount = truncatedText.length
        )
        contextItems.add(item)
        return item
    }
    
    /**
     * 添加文件到上下文
     */
    fun addFile(file: VirtualFile): ContextItem? {
        return try {
            if (isImageFile(file)) {
                addImageFile(file)
            } else if (isTextFile(file)) {
                addTextFile(file)
            } else {
                logger.warn("Unsupported file type: ${file.fileType.name}")
                null
            }
        } catch (e: Exception) {
            logger.error("Failed to add file: ${file.path}", e)
            null
        }
    }
    
    /**
     * 批量添加文件
     */
    fun addFiles(files: Collection<VirtualFile>): List<ContextItem> {
        return files.mapNotNull { addFile(it) }
    }
    
    /**
     * 移除上下文项
     */
    fun removeContextItem(itemId: String): Boolean {
        return contextItems.removeAll { it.id == itemId }
    }
    
    /**
     * 清空所有上下文
     */
    fun clearAllContext() {
        contextItems.clear()
    }
    
    /**
     * 获取上下文的总字符数
     */
    fun getTotalCharacterCount(): Int {
        return contextItems.sumOf { it.characterCount }
    }
    
    /**
     * 检查是否超过 token 限制（简化为字符数检查）
     */
    fun isOverLimit(): Boolean {
        return getTotalCharacterCount() > settings.maxContextLength * 4  // 粗略估计：1 token ≈ 4 chars
    }
    
    /**
     * 智能截断内容
     */
    private fun truncateIfNeeded(content: String, maxLength: Int = settings.maxContextLength * 4): String {
        if (content.length <= maxLength) {
            return content
        }
        
        // 保留开头和结尾，中间用省略号代替
        val previewLength = 500
        val start = content.substring(0, previewLength)
        val end = content.substring(content.length - previewLength)
        
        return "$start\n\n... [truncated ${content.length - previewLength * 2} characters] ...\n\n$end"
    }
    
    /**
     * 添加文本文件
     */
    private fun addTextFile(file: VirtualFile): ContextItem {
        val content = String(file.contentsToByteArray())
        val truncatedContent = truncateIfNeeded(content)
        
        val item = ContextItem(
            type = ContextType.FILE,
            content = truncatedContent,
            displayName = file.name,
            filePath = file.path,
            characterCount = truncatedContent.length
        )
        contextItems.add(item)
        return item
    }
    
    /**
     * 添加图片文件（转为 Base64）
     */
    private fun addImageFile(file: VirtualFile): ContextItem {
        val bytes = file.contentsToByteArray()
        val base64Data = Base64.getEncoder().encodeToString(bytes)
        
        // 构建 data URI
        val mimeType = when (file.extension?.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            else -> "application/octet-stream"
        }
        
        val dataUri = "data:$mimeType;base64,$base64Data"
        
        val item = ContextItem(
            type = ContextType.IMAGE,
            content = "[Image: ${file.name}]",
            displayName = file.name,
            filePath = file.path,
            isImage = true,
            base64Data = dataUri,
            characterCount = bytes.size
        )
        contextItems.add(item)
        return item
    }
    
    /**
     * 判断是否为图片文件
     */
    private fun isImageFile(file: VirtualFile): Boolean {
        val extension = file.extension?.lowercase()
        return extension in listOf("png", "jpg", "jpeg", "gif", "webp", "bmp")
    }
    
    /**
     * 判断是否为文本文件
     */
    private fun isTextFile(file: VirtualFile): Boolean {
        // 通过文件扩展名判断
        val textExtensions = setOf(
            "txt", "md", "java", "kt", "kts", "py", "js", "ts", "jsx", "tsx",
            "html", "css", "scss", "json", "xml", "yaml", "yml", "sql",
            "sh", "bash", "zsh", "rb", "php", "go", "rs", "cpp", "c", "h",
            "swift", "scala", "groovy", "lua", "r", "pl", "ps1"
        )
        
        val extension = file.extension?.lowercase() ?: ""
        return extension in textExtensions || file.fileType.isPlainText
    }
    
    companion object {
        fun getInstance(project: Project): ContextManager {
            return project.getService(ContextManager::class.java) ?: ContextManager(project)
        }
    }
}
