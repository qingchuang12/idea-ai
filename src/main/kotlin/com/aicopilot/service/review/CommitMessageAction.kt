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
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.CommitMessageI
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import kotlinx.coroutines.runBlocking

/**
 * 根据选中/未提交 diff 生成 Git 提交信息 Action（Phase 2 生产力）。
 * 若在提交对话框上下文中触发，则直接写入提交信息框；否则复制到剪贴板并展示。
 */
class CommitMessageAction : AnAction(
    "Generate Commit Message",
    "Use AI to write a commit message from your changes",
    null
) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && ModelRegistry.getInstance().getActiveModel() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val model = ModelRegistry.getInstance().getActiveModel() ?: run {
            Messages.showErrorDialog(project, "请先在设置中配置并启用一个模型。", "AI Commit")
            return
        }

        // 优先使用提交对话框中已选中的变更；否则用全部未提交变更
        val diff = e.getData(VcsDataKeys.SELECTED_CHANGES)
            ?.takeIf { it.isNotEmpty() }
            ?.let { changes ->
                buildString {
                    for (change in changes) {
                        val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: "unknown"
                        append("## File: $path (${change.type})\n")
                        change.beforeRevision?.content?.let { append("### Before\n```\n$it\n```\n") }
                        change.afterRevision?.content?.let { append("### After\n```\n$it\n```\n") }
                    }
                }
            }
            ?: CodeReviewAction.collectUncommittedDiff(project)

        if (diff.isBlank() || diff.contains("(no uncommitted changes)")) {
            Messages.showInfoMessage("当前没有可用于生成提交信息的变更。", "AI Commit")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI: Writing Commit", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.text = "Composing commit message..."
                val message = runBlocking {
                    ChatService().completeText(
                        systemPrompt = "You are an expert at writing Git commit messages. " +
                            "Given the code diff, produce a concise, conventional-commit-style message (type: summary, then optional body). " +
                            "Return ONLY the commit message text, no markdown fences or extra commentary.",
                        userPrompt = "Diff:\n$diff",
                        model = model
                    ).getOrNull()
                } ?: return

                val clean = CodeFence.extract(message) ?: message
                ApplicationManager.getApplication().invokeLater {
                    applyCommitMessage(project, e, clean)
                }
            }
        })
    }

    private fun applyCommitMessage(project: Project, e: AnActionEvent, message: String) {
        val commitControl = e.getData(VcsDataKeys.COMMIT_MESSAGE_CONTROL)
        if (commitControl != null) {
            commitControl.setCommitMessage(message)
            return
        }
        // 回退：复制到剪贴板并提示
        try {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(message), null)
        } catch (_: Exception) { }
        AIChatPanel.find(project)?.showGeneratedResult("Commit Message", "```\n$message\n```")
            ?: Messages.showInfoMessage(project, message, "AI Commit Message (copied to clipboard)")
    }
}
