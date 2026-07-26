package com.aicopilot.model

import java.util.UUID

/**
 * Agent 运行模式。
 * - LIGHT：轻量即时模式，直接进入工具调用循环，端到端完成任务。
 * - HEAVY：重量级规划模式，先产出 To-dos/Plan，用户确认后按步骤执行，支持看板与快照回滚。
 */
enum class AgentMode(val display: String) {
    LIGHT("Light（即时）"),
    HEAVY("Heavy（规划）")
}

/**
 * 多角色专家。执行时按角色注入不同的系统提示词，形成协同分工。
 */
enum class AgentRole(val display: String, val preamble: String) {
    ARCHITECT(
        "架构师",
        "你是资深软件架构师。聚焦模块划分、接口设计与技术选型的合理性，产出清晰、可落地的设计与目录结构建议。"
    ),
    CODER(
        "工程师",
        "你是资深工程师。严格按需求实现功能，遵循现有代码风格与工程约定，改动最小且可编译运行。"
    ),
    REVIEWER(
        "评审员",
        "你是严谨的代码评审员。检查正确性、边界条件、异常处理、安全与性能问题，给出可操作的改进建议。"
    ),
    TESTER(
        "测试工程师",
        "你是测试工程师。针对目标代码设计并生成覆盖核心路径与边界情况的单元测试。"
    );

    companion object {
        fun from(name: String?): AgentRole =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: CODER
    }
}

/** 单个任务步骤的状态。 */
enum class TaskStepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

/** 整体任务状态。 */
enum class TaskStatus { DRAFT, PLANNED, RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * 任务步骤（看板卡片）。可变，以便执行过程中原地更新状态与结果。
 */
data class TaskStep(
    val id: String = UUID.randomUUID().toString(),
    var title: String,
    var detail: String = "",
    var role: AgentRole = AgentRole.CODER,
    var status: TaskStepStatus = TaskStepStatus.PENDING,
    var result: String = ""
)

/**
 * 一次 Agent 重量级任务的完整状态，供任务看板订阅渲染。
 */
data class AgentTask(
    val id: String = UUID.randomUUID().toString(),
    val goal: String,
    val steps: MutableList<TaskStep> = mutableListOf(),
    var status: TaskStatus = TaskStatus.DRAFT,
    var snapshotLabel: String? = null,
    val recommendations: MutableList<Recommendation> = mutableListOf(),
    val log: MutableList<String> = mutableListOf()
) {
    fun progress(): Double {
        if (steps.isEmpty()) return 0.0
        val done = steps.count { it.status == TaskStepStatus.DONE || it.status == TaskStepStatus.SKIPPED }
        return done.toDouble() / steps.size
    }
}

/** 结果推荐卡片的动作类型。 */
enum class RecommendationKind { RUN_TESTS, VIEW_DIFF, OPEN_FILES, ROLLBACK, CUSTOM }

/**
 * 任务完成后浮现的结果推荐卡片。
 */
data class Recommendation(
    val title: String,
    val description: String,
    val kind: RecommendationKind,
    val payload: String = ""
)
