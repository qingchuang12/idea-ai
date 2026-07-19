package com.aicopilot.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import java.util.*

/**
 * 聊天记录持久化状态
 */
@State(
    name = "com.aicopilot.settings.ChatHistoryState",
    storages = [Storage("ai-copilot-chat-history.xml")]
)
class ChatHistoryState : PersistentStateComponent<ChatHistoryState> {
    
    var sessions: MutableList<ChatSession> = mutableListOf()
    var currentSessionId: String? = null
    
    data class ChatSession(
        var id: String = UUID.randomUUID().toString(),
        var title: String = "New Chat",
        var createdAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis(),
        var messages: MutableList<ChatMessage> = mutableListOf()
    )
    
    data class ChatMessage(
        var role: String,      // "user", "assistant", "system"
        var content: String,
        var timestamp: Long = System.currentTimeMillis(),
        var contextFiles: List<String> = emptyList()  // 关联的文件路径
    )
    
    fun createNewSession(): ChatSession {
        val session = ChatSession()
        sessions.add(session)
        currentSessionId = session.id
        return session
    }
    
    fun getCurrentSession(): ChatSession? {
        return currentSessionId?.let { id ->
            sessions.find { it.id == id }
        } ?: sessions.lastOrNull()
    }
    
    fun getSessionById(id: String): ChatSession? {
        return sessions.find { it.id == id }
    }
    
    fun deleteSession(id: String) {
        sessions.removeAll { it.id == id }
        if (currentSessionId == id) {
            currentSessionId = sessions.lastOrNull()?.id
        }
    }
    
    override fun getState(): ChatHistoryState = this
    
    override fun loadState(state: ChatHistoryState) {
        XmlSerializerUtil.copyBean(state, this)
    }
    
    companion object {
        fun getInstance(): ChatHistoryState = 
            com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(ChatHistoryState::class.java)
    }
}
