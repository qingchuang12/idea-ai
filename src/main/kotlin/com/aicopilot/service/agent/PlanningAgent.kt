package com.aicopilot.service.agent

import com.aicopilot.model.AgentRole
import com.aicopilot.model.ModelConfig
import com.aicopilot.model.TaskStep
import com.aicopilot.service.ChatService
import com.google.gson.JsonParser

/**
 * 规划智能体：把一个高层目标拆解为一组带角色的可执行步骤（To-dos）。
 * 用于 Agent 重量级模式，在实际执行前给出计划供用户确认。
 */
class PlanningAgent(private val chatService: ChatService = ChatService()) {

    private val systemPrompt = """
        你是一个资深技术负责人。请把用户的编码目标拆解为有序、可执行的步骤清单。
        每个步骤指派一个最合适的角色：ARCHITECT（架构设计）、CODER（编码实现）、REVIEWER（评审）、TESTER（测试）。
        步骤要具体、聚焦、粒度适中（通常 3-6 步），避免过度拆分。
        只输出如下 JSON，不要输出任何其它文字或代码块标记：
        {"steps":[{"title":"简短标题","detail":"该步骤要做什么","role":"CODER"}]}
    """.trimIndent()

    /**
     * 生成计划步骤。失败时返回一个兜底的单步计划。
     */
    suspend fun plan(goal: String, model: ModelConfig): List<TaskStep> {
        val result = chatService.completeText(systemPrompt, "目标：$goal", model, temperature = 0.2)
        val raw = result.getOrNull() ?: return fallback(goal)
        return parseSteps(raw).ifEmpty { fallback(goal) }
    }

    private fun parseSteps(raw: String): List<TaskStep> {
        return try {
            val jsonStr = extractJson(raw) ?: return emptyList()
            val obj = JsonParser.parseString(jsonStr).asJsonObject
            val arr = obj.getAsJsonArray("steps") ?: return emptyList()
            arr.mapNotNull { el ->
                val o = el.asJsonObject
                val title = o.get("title")?.asString?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                TaskStep(
                    title = title,
                    detail = o.get("detail")?.asString ?: "",
                    role = AgentRole.from(o.get("role")?.asString)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** 从可能包含 ```json 围栏或前后噪声的文本中截取首个 JSON 对象。 */
    private fun extractJson(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }

    private fun fallback(goal: String): List<TaskStep> = listOf(
        TaskStep(title = "完成目标", detail = goal, role = AgentRole.CODER)
    )
}
