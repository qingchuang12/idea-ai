package com.aicopilot.service.understanding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/**
 * 跨会话持久化记忆网络（应用级）。
 *
 * Phase 5「深度工程理解」的记忆层：把用户偏好、项目背景、关键事实沉淀为带标签的记忆条目，
 * 在后续会话中可被 Agent 工具（memory_recall / memory_save）召回或写入，
 * 也可在每次对话前自动注入相关记忆作为系统上下文。
 */
@State(
    name = "com.aicopilot.service.understanding.MemoryStore",
    storages = [Storage("ai-copilot-memory.xml")]
)
class MemoryStore : PersistentStateComponent<MemoryStore.State> {

    enum class Kind { FACT, PREFERENCE, PROJECT, FEEDBACK }

    data class Entry(
        var key: String = "",
        var content: String = "",
        var kind: String = Kind.FACT.name,
        var tags: String = "",            // 逗号分隔
        var createdAt: Long = System.currentTimeMillis(),
        var updatedAt: Long = System.currentTimeMillis()
    )

    class State {
        @XCollection(style = XCollection.Style.v2)
        var entries: MutableList<Entry> = mutableListOf()
    }

    private val state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    fun remember(key: String, content: String, kind: String = Kind.FACT.name, tags: String = ""): Entry {
        val existing = state.entries.firstOrNull { it.key == key }
        val entry = existing ?: Entry(key = key).also { state.entries.add(it) }
        entry.content = content
        entry.kind = kind
        entry.tags = tags
        entry.updatedAt = System.currentTimeMillis()
        if (existing == null) entry.createdAt = entry.updatedAt
        return entry
    }

    fun forget(key: String): Boolean = state.entries.removeAll { it.key == key }

    fun all(): List<Entry> = state.entries.toList()

    fun count(): Int = state.entries.size

    /**
     * 召回：按 key / content / tags 包含查询词匹配，返回最相关条目。
     */
    fun recall(query: String, limit: Int = 5): List<Entry> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return state.entries.take(limit)
        val terms = q.split(Regex("\\s+")).filter { it.length >= 2 }
        return state.entries.mapNotNull { e ->
            val hay = "${e.key} ${e.content} ${e.tags}".lowercase()
            val score = terms.count { hay.contains(it) }
            if (score == 0) null else e to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    /**
     * 生成可注入系统提示的相关记忆文本。
     */
    fun asSystemContext(queryHint: String = ""): String {
        val relevant = if (queryHint.isBlank()) state.entries.take(8) else recall(queryHint, 8)
        if (relevant.isEmpty()) return ""
        return buildString {
            appendLine("# Relevant Memory（来自跨会话记忆网络）")
            relevant.forEach { e ->
                appendLine("- [${e.kind}] ${e.key}: ${e.content}")
            }
        }
    }

    companion object {
        fun getInstance(): MemoryStore =
            ApplicationManager.getApplication().getService(MemoryStore::class.java)
    }
}
