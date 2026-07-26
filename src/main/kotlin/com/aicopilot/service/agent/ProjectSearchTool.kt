package com.aicopilot.service.agent

import com.aicopilot.service.agent.ToolUtils.intArg
import com.aicopilot.service.agent.ToolUtils.stringArg
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 在工程内进行文本搜索（返回 file:line 匹配）。
 */
class ProjectSearchTool : AITool {
    override val name = "project_search"
    override val description = "在工程内按关键字搜索文本，返回匹配的文件、行号与该行内容。"
    override val parameters = ToolSchema.obj(
        "query" to ToolSchema.string("要搜索的关键字或子串"),
        "maxResults" to ToolSchema.integer("最多返回结果数，默认 50"),
        required = listOf("query")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val query = args.stringArg("query")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.error("missing 'query'")
        val maxResults = args.intArg("maxResults", 50).coerceIn(1, 500)
        val basePath = ctx.project.basePath ?: return@withContext ToolResult.error("no project base path")

        val root = File(basePath)
        if (!root.exists()) return@withContext ToolResult.error("project base path not found")

        val results = mutableListOf<String>()
        try {
            root.walkTopDown()
                .onEnter { dir -> dir.name !in ToolUtils.SKIP_DIRS }
                .filter { it.isFile && it.extension.lowercase() !in ToolUtils.BINARY_EXTS && it.length() < 2_000_000 }
                .forEach { file ->
                    if (results.size >= maxResults) return@forEach
                    try {
                        file.useLines { lines ->
                            lines.forEachIndexed { idx, line ->
                                if (results.size >= maxResults) return@forEachIndexed
                                if (line.contains(query, ignoreCase = true)) {
                                    val rel = file.relativeTo(root).path.replace('\\', '/')
                                    results.add("$rel:${idx + 1}: ${line.trim().take(200)}")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // 跳过无法读取的文件
                    }
                }
        } catch (e: Exception) {
            return@withContext ToolResult.error(e.message ?: "search failed")
        }

        if (results.isEmpty()) ToolResult.ok("No matches found for '$query'.")
        else ToolResult.ok(results.joinToString("\n"))
    }
}
