package com.aicopilot.service.agent

import com.aicopilot.service.agent.ToolUtils.stringArg
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.vfs.LocalFileSystem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 写入 / 修改工程内文件。属于危险操作，默认需要用户确认。
 */
class FileWriteTool : AITool {
    override val name = "file_write"
    override val description = "创建或覆盖工程内文件。path 为路径，content 为完整文件内容。"
    override val parameters = ToolSchema.obj(
        "path" to ToolSchema.string("目标文件路径（相对工程根或绝对）"),
        "content" to ToolSchema.string("要写入的完整文件内容"),
        required = listOf("path", "content")
    )
    override val requiresConfirmation = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path") ?: return@withContext ToolResult.error("missing 'path'")
        val content = args.stringArg("content") ?: ""
        val ioFile = ToolUtils.resolveFile(ctx.project.basePath, path)
            ?: return@withContext ToolResult.error("cannot resolve path: $path")

        val result = StringBuilder()
        ApplicationManager.getApplication().invokeAndWait {
            WriteCommandAction.runWriteCommandAction(ctx.project, "AI Write File", null, {
                try {
                    ioFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
                    val existed = ioFile.exists()
                    ioFile.writeText(content)
                    LocalFileSystem.getInstance().refreshAndFindFileByIoFile(ioFile)
                    result.append(if (existed) "updated" else "created").append(": ").append(ioFile.path)
                } catch (e: Exception) {
                    result.append("ERROR: ").append(e.message)
                }
            })
        }
        if (result.startsWith("ERROR")) ToolResult.error(result.toString())
        else ToolResult.ok(result.toString())
    }
}
