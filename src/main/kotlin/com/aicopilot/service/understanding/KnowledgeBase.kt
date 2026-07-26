package com.aicopilot.service.understanding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil
import com.intellij.util.xmlb.annotations.XCollection

/**
 * 企业级知识库引擎（应用级，持久化）。
 *
 * Phase 5「深度工程理解」的知识接入层：允许把文件 / URL / 自定义文本沉淀为可检索的知识文档，
 * 由 Agent 工具（knowledge_query）或「AI Knowledge」视窗检索复用。
 *
 * MVP 采用轻量关键词检索；后续可平滑替换为向量检索而不改变对外接口。
 */
@State(
    name = "com.aicopilot.service.understanding.KnowledgeBase",
    storages = [Storage("ai-copilot-knowledge.xml")]
)
class KnowledgeBase : PersistentStateComponent<KnowledgeBase.State> {

    /** 知识文档类型 */
    enum class SourceType { FILE, URL, TEXT }

    data class Doc(
        var id: String = java.util.UUID.randomUUID().toString(),
        var title: String = "",
        var source: String = "",
        var type: String = SourceType.TEXT.name,
        var content: String = "",
        var addedAt: Long = System.currentTimeMillis()
    )

    class State {
        @XCollection(style = XCollection.Style.v2)
        var docs: MutableList<Doc> = mutableListOf()
    }

    private val state = State()

    override fun getState(): State = state
    override fun loadState(s: State) = XmlSerializerUtil.copyBean(s, state)

    fun addText(title: String, content: String): Doc {
        val doc = Doc(title = title, source = title, type = SourceType.TEXT.name, content = content)
        state.docs.add(doc)
        return doc
    }

    fun addFile(path: String, content: String) {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        state.docs.add(Doc(title = name, source = path, type = SourceType.FILE.name, content = content))
    }

    fun addUrl(url: String, content: String) {
        state.docs.add(Doc(title = url, source = url, type = SourceType.URL.name, content = content))
    }

    fun remove(id: String) {
        state.docs.removeAll { it.id == id }
    }

    fun all(): List<Doc> = state.docs.toList()

    fun count(): Int = state.docs.size

    /**
     * 关键词检索：按命中词数给文档打分，返回片段。
     */
    fun query(q: String, limit: Int = 5): List<Pair<Doc, String>> {
        val terms = q.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        if (terms.isEmpty()) return emptyList()
        val scored = state.docs.mapNotNull { doc ->
            val text = doc.content.lowercase()
            var score = 0
            for (t in terms) if (text.contains(t)) score++
            if (score == 0) null else doc to score
        }.sortedByDescending { it.second }.take(limit)
        return scored.map { (doc, score) ->
            doc to buildSnippet(doc.content, terms, 400)
        }
    }

    private fun buildSnippet(text: String, terms: List<String>, max: Int): String {
        val lower = text.lowercase()
        val hit = terms.firstNotNullOfOrNull { t -> lower.indexOf(t).takeIf { i -> i >= 0 } } ?: 0
        val start = (hit - 120).coerceAtLeast(0)
        val end = (start + max).coerceAtMost(text.length)
        return text.substring(start, end).replace("\n{3,}".toRegex(), "\n\n")
    }

    companion object {
        fun getInstance(): KnowledgeBase =
            ApplicationManager.getApplication().getService(KnowledgeBase::class.java)
    }
}
