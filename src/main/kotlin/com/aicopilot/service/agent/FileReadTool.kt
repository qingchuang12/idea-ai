package com.aicopilot.service.agent

import com.aicopilot.service.agent.ToolUtils.stringArg
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 读取工程内文件内容。
 */
class FileReadTool : AITool {
    override val name = "file_read"
    override val description = "读取工程内指定文件的文本内容。path 支持相对工程根目录或绝对路径。"
    override val parameters = ToolSchema.obj(
        "path" to ToolSchema.string("文件路径（相对工程根或绝对）"),
        required = listOf("path")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path") ?: return@withContext ToolResult.error("missing 'path'")
        val file = ToolUtils.resolveFile(ctx.project.basePath, path)
            ?: return@withContext ToolResult.error("cannot resolve path: $path")
        if (!file.exists() || !file.isFile) return@withContext ToolResult.error("file not found: $path")
        try {
            val text = file.readText()
            val out = if (text.length > 20000) text.substring(0, 20000) + "\n... [truncated ${text.length - 20000} chars]" else text
            ToolResult.ok(out)
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "read failed")
        }
    }
}
