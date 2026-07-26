package com.aicopilot.service.review

import com.aicopilot.service.ChatService
import com.aicopilot.settings.ModelRegistry
import com.aicopilot.ui.AIChatPanel
import com.aicopilot.util.CodeFence
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager
import kotlinx.coroutines.runBlocking

/**
 * 未提交代码评审 Action（Phase 2 生产力）。
 * 收集 Git 未提交变更的 before/after 内容，调用 LLM 生成评审意见，并写入 AI 聊天面板。
 */
class CodeReviewAction : AnAction(
    "Review Uncommitted Changes",
    "Use AI to review your uncommitted changes",
    null
) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ModelRegistry.getInstance().getActiveModel() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = ModelRegistry.getInstance().getActiveModel() ?: run {
            Messages.showErrorDialog(project, "请先在设置中配置并启用一个模型。", "AI Code Review")
            return
        }

        val diff = collectUncommittedDiff(project)
        if (diff.isBlank()) {
            Messages.showInfoMessage("当前没有未提交的变更。", "AI Code Review")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI: Reviewing Changes", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.text = "Analyzing changes..."
                val review = runBlocking {
                    ChatService().completeText(
                        systemPrompt = "You are a meticulous senior code reviewer. Review the following uncommitted changes. " +
                            "Point out bugs, risks, potential exceptions, and style issues. Be concise and use bullet points. " +
                            "Return Markdown.",
                        userPrompt = "Uncommitted changes:\n$diff",
                        model = model
                    ).getOrNull()
                } ?: return

                val markdown = CodeFence.extractOrRaw(review)
                ApplicationManager.getApplication().invokeLater {
                    AIChatPanel.find(project)?.showGeneratedResult("Code Review", markdown)
                        ?: Messages.showInfoMessage(project, markdown, "AI Code Review")
                }
            }
        })
    }

    companion object {
        /** 收集未提交变更（before/after 内容），构造评审上下文。 */
        fun collectUncommittedDiff(project: Project): String = buildString {
            val changes = ChangeListManager.getInstance(project).allChanges
            if (changes.isEmpty()) {
                append("(no uncommitted changes)")
                return@buildString
            }
            for (change in changes) {
                val path = change.afterRevision?.file?.path
                    ?: change.beforeRevision?.file?.path ?: "unknown"
                append("## File: $path (${change.type})\n")
                val before = change.beforeRevision?.content
                val after = change.afterRevision?.content
                if (before != null) append("### Before\n```\n$before\n```\n")
                if (after != null) append("### After\n```\n$after\n```\n")
            }
        }
    }
}
