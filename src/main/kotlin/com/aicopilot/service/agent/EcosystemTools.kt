package com.aicopilot.service.agent

import com.aicopilot.ecosystem.McpClient
import com.aicopilot.ecosystem.RulesLoader
import com.aicopilot.ecosystem.SkillMarket
import com.aicopilot.service.agent.ToolUtils.stringArg
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 项目规则查询工具：列出当前工程已加载的 .rules 规则。
 */
class RulesQueryTool : AITool {
    override val name = "rules_list"
    override val description = "列出当前工程已加载的项目规则（.rules 目录或 *.rules 文件），用于了解编码规范与约束。"
    override val parameters = ToolSchema.obj()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val rules = RulesLoader.getInstance(ctx.project).listRules()
        if (rules.isEmpty()) return@withContext ToolResult.ok("当前工程没有已加载的规则文件。")
        val sb = StringBuilder("已加载 ${rules.size} 条项目规则：\n")
        rules.forEach { sb.append("- ${it.name}：${it.content.take(120).replace("\n", " ")}\n") }
        ToolResult.ok(sb.toString())
    }
}

/**
 * 技能库工具：列出可用技能或渲染某技能指令供本次任务使用（本地手动添加，无联网市场）。
 */
class SkillTool : AITool {
    override val name = "skill"
    override val description = "查询或应用技能库中的技能（如 security-audit / perf-review / db-modeling / code-explainer）。action=list 列出全部；action=run 渲染某技能的系统指令。"
    override val parameters = ToolSchema.obj(
        "action" to ToolSchema.string("list 或 run"),
        "name" to ToolSchema.string("技能名称（action=run 时必填）"),
        required = listOf("action")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val action = args.stringArg("action")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("missing 'action'")
        val market = SkillMarket.getInstance()
        return@withContext when (action) {
            "list" -> {
                val sb = StringBuilder("可用技能：\n")
                market.getAllSkills().forEach { sb.append("- ${it.name}: ${it.description}\n") }
                ToolResult.ok(sb.toString())
            }
            "run" -> {
                val name = args.stringArg("name")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("action=run 需要 'name'")
                val prompt = market.renderAsPrompt(name)
                if (prompt.startsWith("skill not found")) ToolResult.error(prompt) else ToolResult.ok(prompt)
            }
            else -> ToolResult.error("unknown action: $action (use list|run)")
        }
    }
}

/**
 * 列出已连接的 MCP 服务器及其工具。
 */
class McpListTool : AITool {
    override val name = "mcp_list"
    override val description = "列出已连接的 MCP 服务器与各自暴露的远端工具（需在设置/MCP 面板中先连接）。"
    override val parameters = ToolSchema.obj()

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val client = McpClient.getInstance()
        val servers = client.listServers()
        if (servers.isEmpty()) return@withContext ToolResult.ok("当前没有已连接的 MCP 服务器。请在「AI Knowledge / MCP」面板中添加并连接。")
        val sb = StringBuilder("已连接 MCP 服务器：\n")
        servers.forEach { s ->
            sb.append("\n● $s\n")
            client.listTools(s).forEach { t -> sb.append("  - ${t.name}: ${t.description.take(100)}\n") }
        }
        ToolResult.ok(sb.toString())
    }
}

/**
 * 调用远端 MCP 工具（需服务器已连接）。
 */
class McpCallTool : AITool {
    override val name = "mcp_call"
    override val description = "调用某个已连接 MCP 服务器暴露的工具。需提供服务器名、工具名与 JSON 参数。"
    override val parameters = ToolSchema.obj(
        "server" to ToolSchema.string("MCP 服务器名"),
        "tool" to ToolSchema.string("工具名"),
        "arguments" to ToolSchema.string("JSON 格式的参数对象，如 {\"path\":\"/x\"}"),
        required = listOf("server", "tool")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.IO) {
        val server = args.stringArg("server")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("missing 'server'")
        val tool = args.stringArg("tool")?.takeIf { it.isNotBlank() } ?: return@withContext ToolResult.error("missing 'tool'")
        val arguments = args.stringArg("arguments") ?: "{}"
        val client = McpClient.getInstance()
        if (!client.isConnected(server)) return@withContext ToolResult.error("MCP 服务器未连接：$server")
        val result = client.callTool(server, tool, arguments)
        ToolResult(result.isSuccess, result.getOrDefault("ERROR: mcp_call failed"))
    }
}
