package com.aicopilot.util

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.Messages
import java.io.IOException

/**
 * 异常处理和用户提示工具类
 */
object ExceptionHandler {
    
    private val logger = Logger.getInstance(ExceptionHandler::class.java)
    
    /**
     * 处理网络相关异常并显示友好提示
     */
    fun handleNetworkException(e: Exception, context: String = "") {
        logger.warn("Network error in $context: ${e.message}", e)
        
        val message = when (e) {
            is IOException -> {
                when {
                    e.message?.contains("timeout", ignoreCase = true) == true ->
                        "Request timed out. Please check your network connection or increase the timeout setting."
                    e.message?.contains("connection refused", ignoreCase = true) == true ->
                        "Connection refused. Please verify the server is running and the URL is correct."
                    e.message?.contains("unknown host", ignoreCase = true) == true ->
                        "Unknown host. Please check the base URL configuration."
                    else -> "Network error: ${e.message ?: "Unknown error"}"
                }
            }
            else -> "Error: ${e.message ?: "Unknown error"}"
        }
        
        showMessage("AI Copilot - Network Error", message, Messages.getErrorIcon())
    }
    
    /**
     * 处理 API 认证错误
     */
    fun handleAuthError(message: String) {
        logger.warn("Authentication error: $message")
        showMessage(
            "AI Copilot - Authentication Error",
            "Invalid API Key or authentication failed. Please check your settings.",
            Messages.getErrorIcon()
        )
    }
    
    /**
     * 处理无效配置错误
     */
    fun handleConfigError(message: String) {
        logger.warn("Configuration error: $message")
        showMessage(
            "AI Copilot - Configuration Error",
            "Invalid configuration: $message\nPlease check Settings > Tools > AI Copilot",
            Messages.getWarningIcon()
        )
    }
    
    /**
     * 处理通用错误
     */
    fun handleGeneralError(e: Exception, context: String = "") {
        logger.error("Error in $context: ${e.message}", e)
        
        val message = buildString {
            append("An unexpected error occurred")
            if (context.isNotEmpty()) {
                append(" while $context")
            }
            append(":\n${e.message ?: "Unknown error"}")
        }
        
        showMessage("AI Copilot - Error", message, Messages.getErrorIcon())
    }
    
    /**
     * 显示信息提示
     */
    fun showInfo(message: String, title: String = "AI Copilot") {
        showMessage(title, message, Messages.getInformationIcon())
    }
    
    /**
     * 显示警告提示
     */
    fun showWarning(message: String, title: String = "AI Copilot") {
        showMessage(title, message, Messages.getWarningIcon())
    }
    
    /**
     * 统一的消息显示方法
     */
    private fun showMessage(title: String, message: String, icon: javax.swing.Icon) {
        try {
            // 在 EDT 线程中显示
            if (java.awt.EventQueue.isDispatchThread()) {
                Messages.showMessageDialog(message, title, icon)
            } else {
                java.awt.EventQueue.invokeLater {
                    Messages.showMessageDialog(message, title, icon)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to show message dialog", e)
            // Fallback: log to console
            println("[$title] $message")
        }
    }
    
    /**
     * 执行带异常处理的 suspending 函数
     */
    suspend fun <T> runCatchingWithHandler(
        context: String,
        block: suspend () -> T
    ): Result<T> {
        return try {
            Result.success(block())
        } catch (e: IOException) {
            handleNetworkException(e, context)
            Result.failure(e)
        } catch (e: SecurityException) {
            handleAuthError(e.message ?: "Security error")
            Result.failure(e)
        } catch (e: IllegalArgumentException) {
            handleConfigError(e.message ?: "Invalid argument")
            Result.failure(e)
        } catch (e: Exception) {
            handleGeneralError(e, context)
            Result.failure(e)
        }
    }
}
