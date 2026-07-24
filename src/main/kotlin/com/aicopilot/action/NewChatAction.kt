package com.aicopilot.action

import com.aicopilot.settings.ChatHistoryState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger

/**
 * 新建聊天会话 Action
 */
class NewChatAction : AnAction() {
    
    private val logger = Logger.getInstance(NewChatAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val historyState = ChatHistoryState.getInstance()
        
        // 创建新会话
        val newSession = historyState.createNewSession()
        
        logger.info("Created new chat session: ${newSession.id}")
        
        // TODO: 通知 UI 刷新会话列表
        // 可以通过消息总线或直接调用 UI 组件实现
    }
    
    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = true
    }
}
