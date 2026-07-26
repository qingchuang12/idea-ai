package com.aicopilot.service.agent

import com.aicopilot.service.agent.ToolUtils.stringArg
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 执行终端命令。属于危险操作，默认需要用户确认。
 */
class TerminalTool : AITool {
    override val name = "run_terminal"
    override val description = "在工程根目录执行一条 shell / cmd 命令，返回标准输出与退出码。"
    override val parameters = ToolSchema.obj(
        "command" to ToolSchema.string("要执行的命令"),
        required = listOf("command")
    )
    override val requiresConfirmation = true

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val command = args.stringArg("command") ?: return@withContext ToolResult.error("missing 'command'")
        val basePath = ctx.project.basePath ?: return@withContext ToolResult.error("no project base path")
        try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            val pb = ProcessBuilder().apply {
                command(if (isWindows) listOf("cmd.exe", "/c", command) else listOf("bash", "-lc", command))
                directory(File(basePath))
                redirectErrorStream(true)
            }
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return@withContext ToolResult.error("command timed out after 60s")
            }
            val trimmed = if (output.length > 15000) output.substring(0, 15000) + "\n... [truncated]" else output
            ToolResult.ok("exit=${process.exitValue()}\n$trimmed")
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "execution failed")
        }
    }
}
