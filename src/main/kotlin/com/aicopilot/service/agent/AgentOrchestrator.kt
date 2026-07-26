package com.aicopilot.service.agent

import com.aicopilot.model.AgentEvent
import com.aicopilot.model.AgentTask
import com.aicopilot.model.ModelConfig
import com.aicopilot.model.Recommendation
import com.aicopilot.model.RecommendationKind
import com.aicopilot.model.TaskStatus
import com.aicopilot.model.TaskStep
import com.aicopilot.model.TaskStepStatus
import com.aicopilot.service.ContextManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Agent 编排器（项目级）。驱动重量级模式的完整闭环：
 * 规划 → 用户确认 → 建立快照 → 多角色分步执行 → 汇总产物与结果推荐卡片 → 支持回滚。
 *
 * 通过监听器把当前 [AgentTask] 状态实时推送给任务看板 UI。
 */
@Service(Service.Level.PROJECT)
class AgentOrchestrator(private val project: Project) {

    @Volatile
    var currentTask: AgentTask? = null
        private set

    private val listeners = mutableListOf<(AgentTask) -> Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val timeFmt = SimpleDateFormat("HH:mm:ss")

    fun addListener(l: (AgentTask) -> Unit) {
        listeners.add(l)
        currentTask?.let { l(it) }
    }

    fun removeListener(l: (AgentTask) -> Unit) {
        listeners.remove(l)
    }

    private fun notifyUpdate() {
        val task = currentTask ?: return
        listeners.toList().forEach { runCatching { it(task) } }
    }

    private fun log(msg: String) {
        currentTask?.log?.add("[${timeFmt.format(Date())}] $msg")
        notifyUpdate()
    }

    /**
     * 生成计划并进入待确认状态。异步执行，通过监听器回推。
     */
    fun startPlanning(goal: String, model: ModelConfig) {
        val task = AgentTask(goal = goal, status = TaskStatus.DRAFT)
        currentTask = task
        notifyUpdate()
        log("正在为目标生成计划…")
        scope.launch {
            val steps = try {
                PlanningAgent().plan(goal, model)
            } catch (e: Exception) {
                thisLogger().warn("planning failed", e)
                emptyList()
            }
            task.steps.clear()
            task.steps.addAll(steps)
            task.status = TaskStatus.PLANNED
            log("已生成 ${steps.size} 个步骤，等待确认后执行。")
        }
    }

    /**
     * 用户确认计划后开始执行。为每个步骤按角色调用 AgentLoop 完成。
     * @param confirm 工具执行前的权限确认回调。
     */
    fun startExecution(model: ModelConfig, confirm: (String, String) -> Boolean) {
        val task = currentTask ?: return
        if (task.status != TaskStatus.PLANNED) return
        task.status = TaskStatus.RUNNING
        notifyUpdate()

        scope.launch {
            // 执行前建立快照
            val snap = SnapshotManager.getInstance(project)
                .snapshot("AI Agent: ${task.goal.take(40)}")
            task.snapshotLabel = snap
            log(if (snap != null) "已建立执行快照，可在结束后回滚。" else "快照创建失败，继续执行。")

            val ctx = ToolContext(project, confirm)
            val contextItems = ContextManager.getInstance(project).getContextItems()
            var failed = false

            for (step in task.steps) {
                if (step.status == TaskStepStatus.SKIPPED) continue
                step.status = TaskStepStatus.RUNNING
                log("▶ [${step.role.display}] ${step.title}")

                val preamble = step.role.preamble +
                    "\n你正在完成整体目标：「${task.goal}」中的一个步骤。" +
                    "在修改文件或执行命令前请说明意图，完成后给出简洁总结。" +
                    buildExtraContext()
                val stepTask = buildString {
                    append(step.title)
                    if (step.detail.isNotBlank()) append("\n").append(step.detail)
                }

                val collected = StringBuilder()
                try {
                    AgentLoop().run(
                        userTask = stepTask,
                        systemPreamble = preamble,
                        contextItems = contextItems,
                        model = model,
                        ctx = ctx,
                        maxIterations = 6
                    ) { event -> handleStepEvent(step, event, collected) }
                    if (step.status != TaskStepStatus.FAILED) {
                        step.status = TaskStepStatus.DONE
                    }
                } catch (e: Exception) {
                    step.status = TaskStepStatus.FAILED
                    step.result = e.message ?: "执行失败"
                    log("❌ ${step.title} 执行异常：${e.message}")
                }
                if (step.status == TaskStepStatus.FAILED) {
                    failed = true
                    break
                }
                notifyUpdate()
            }

            task.status = if (failed) TaskStatus.FAILED else TaskStatus.COMPLETED
            buildRecommendations(task)
            log(if (failed) "任务执行中断。" else "任务全部完成。")
        }
    }

    private fun buildExtraContext(): String = buildString {
        runCatching {
            val rules = com.aicopilot.ecosystem.RulesLoader.getInstance(project).asSystemPrompt()
            if (rules.isNotBlank()) append("\n\n").append(rules)
        }
        runCatching {
            val mem = com.aicopilot.service.understanding.MemoryStore.getInstance().asSystemContext()
            if (mem.isNotBlank()) append("\n\n").append(mem)
        }
    }

    private fun handleStepEvent(step: TaskStep, event: AgentEvent, collected: StringBuilder) {
        when (event) {
            is AgentEvent.Thinking -> {}
            is AgentEvent.AssistantMessage -> log("· ${event.text.take(200)}")
            is AgentEvent.ToolInvoked -> log("🔧 ${event.call.name} ${event.call.argumentsJson.take(160)}")
            is AgentEvent.ToolFinished ->
                log("${if (event.ok) "✅" else "❌"} ${event.call.name}")
            is AgentEvent.Error -> {
                step.status = TaskStepStatus.FAILED
                step.result = event.message
                log("❌ ${event.message}")
            }
            is AgentEvent.Finished -> {
                collected.append(event.finalText)
                step.result = collected.toString()
            }
        }
    }

    private fun buildRecommendations(task: AgentTask) {
        task.recommendations.clear()
        task.recommendations.add(
            Recommendation("运行测试", "为改动运行工程测试", RecommendationKind.RUN_TESTS)
        )
        task.recommendations.add(
            Recommendation("查看变更", "打开 Git 变更/差异视图", RecommendationKind.VIEW_DIFF)
        )
        if (task.snapshotLabel != null) {
            task.recommendations.add(
                Recommendation("回滚改动", "撤销本次 Agent 的全部文件改动", RecommendationKind.ROLLBACK)
            )
        }
        notifyUpdate()
    }

    /** 跳过某步骤。 */
    fun skipStep(stepId: String) {
        val step = currentTask?.steps?.firstOrNull { it.id == stepId } ?: return
        if (step.status == TaskStepStatus.PENDING) {
            step.status = TaskStepStatus.SKIPPED
            notifyUpdate()
        }
    }

    /** 取消当前任务。 */
    fun cancel() {
        val task = currentTask ?: return
        if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PLANNED) {
            task.status = TaskStatus.CANCELLED
            log("任务已取消。")
        }
    }

    /** 回滚到执行前的快照（必须在 EDT 调用）。 */
    fun rollback(): Boolean {
        val ok = SnapshotManager.getInstance(project).rollback()
        log(if (ok) "已回滚到执行前快照。" else "回滚失败或无可用快照。")
        return ok
    }

    companion object {
        fun getInstance(project: Project): AgentOrchestrator =
            project.getService(AgentOrchestrator::class.java)
    }
}
