package com.aicopilot.service.agent

import com.aicopilot.service.agent.ToolUtils.intArg
import com.aicopilot.service.agent.ToolUtils.stringArg
import com.aicopilot.service.understanding.CodeIndexService
import com.aicopilot.service.understanding.KnowledgeBase
import com.aicopilot.service.understanding.MemoryStore
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 代码库语义检索工具：基于工程索引返回相关文件及片段。
 */
class CodeIndexSearchTool : AITool {
    override val name = "code_index_search"
    override val description = "在工程代码索引中检索与查询相关的文件（基于关键词倒排索引），返回相关文件路径与语言。修改或理解代码前可先调用以定位文件。"
    override val parameters = ToolSchema.obj(
        "query" to ToolSchema.string("检索关键词（自然语言或符号名）"),
        "limit" to ToolSchema.integer("返回结果数量上限（默认 15）"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.error("missing 'query'")
        val limit = args.intArg("limit", 15)
        val svc = CodeIndexService.getInstance(ctx.project)
        if (!svc.isIndexed()) {
            return@withContext ToolResult.ok(
                "工程尚未建立索引。请先在「AI Knowledge」视窗或「Index Codebase」动作中建立索引，再调用本工具。\n（可用 file_search / symbol_lookup 作为替代。）"
            )
        }
        val hits = svc.search(query, limit)
        if (hits.isEmpty()) return@withContext ToolResult.ok("未找到与「$query」相关的文件。")
        val sb = StringBuilder("相关文件（按相关度降序）：\n")
        hits.forEachIndexed { i, h ->
            sb.append("${i + 1}. ${h.filePath}  [${h.language}]  score=${h.score}\n")
        }
        // 附带最相关文件的片段
        val top = hits.first()
        sb.append("\n--- 片段：${top.filePath} ---\n")
        sb.append(svc.snippet(top.filePath))
        ToolResult.ok(sb.toString())
    }
}

/**
 * 知识库检索工具：从企业知识库中检索相关文档片段。
 */
class KnowledgeQueryTool : AITool {
    override val name = "knowledge_query"
    override val description = "在企业知识库中检索与问题相关的文档片段（文件/URL/文本沉淀的知识）。"
    override val parameters = ToolSchema.obj(
        "query" to ToolSchema.string("检索问题或关键词"),
        "limit" to ToolSchema.integer("返回文档数上限（默认 5）"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.error("missing 'query'")
        val limit = args.intArg("limit", 5)
        val kb = KnowledgeBase.getInstance()
        if (kb.count() == 0) return@withContext ToolResult.ok("知识库为空，请先在「AI Knowledge」视窗添加知识文档。")
        val res = kb.query(query, limit)
        if (res.isEmpty()) return@withContext ToolResult.ok("知识库中未检索到相关内容。")
        val sb = StringBuilder("知识库检索结果：\n")
        res.forEachIndexed { i, (doc, snippet) ->
            sb.append("\n[$i] ${doc.title} (${doc.type})\n$snippet\n")
        }
        ToolResult.ok(sb.toString())
    }
}

/**
 * 记忆写入工具：把用户偏好 / 项目背景 / 事实沉淀到跨会话记忆网络。
 */
class MemorySaveTool : AITool {
    override val name = "memory_save"
    override val description = "把一条信息写入跨会话记忆网络（长期记忆），下次会话可自动召回。例如用户偏好、项目约定、关键事实。"
    override val parameters = ToolSchema.obj(
        "key" to ToolSchema.string("记忆的键（唯一标识，如 'user_pref_language'）"),
        "content" to ToolSchema.string("记忆内容"),
        "kind" to ToolSchema.string("类型：FACT / PREFERENCE / PROJECT / FEEDBACK"),
        "tags" to ToolSchema.string("逗号分隔的标签，便于召回"),
        required = listOf("key", "content")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val key = args.stringArg("key")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("missing 'key'")
        val content = args.stringArg("content")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("missing 'content'")
        val kind = args.stringArg("kind") ?: "FACT"
        val tags = args.stringArg("tags") ?: ""
        MemoryStore.getInstance().remember(key, content, kind, tags)
        ToolResult.ok("已写入记忆：$key")
    }
}

/**
 * 记忆召回工具：从跨会话记忆网络中检索相关记忆。
 */
class MemoryRecallTool : AITool {
    override val name = "memory_recall"
    override val description = "从跨会话记忆网络中检索与查询相关的长期记忆（用户偏好 / 项目背景 / 事实）。"
    override val parameters = ToolSchema.obj(
        "query" to ToolSchema.string("召回关键词"),
        "limit" to ToolSchema.integer("返回条数上限（默认 5）"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("missing 'query'")
        val limit = args.intArg("limit", 5)
        val mem = MemoryStore.getInstance()
        if (mem.count() == 0) return@withContext ToolResult.ok("记忆网络为空，暂无可召回内容。")
        val hits = mem.recall(query, limit)
        if (hits.isEmpty()) return@withContext ToolResult.ok("未召回与「$query」相关的记忆。")
        val sb = StringBuilder("召回记忆：\n")
        hits.forEach { e ->
            sb.append("- [${e.kind}] ${e.key}: ${e.content} (tags: ${e.tags})\n")
        }
        ToolResult.ok(sb.toString())
    }
}
