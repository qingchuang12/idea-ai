package com.aicopilot.action

import com.aicopilot.service.understanding.CodeIndexService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * 触发工程代码库索引（Phase 5）。
 */
class IndexCodebaseAction : AnAction(
    "Index Codebase",
    "Build the AI code index for this project",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val svc = CodeIndexService.getInstance(project)
        svc.indexAsync { stats ->
            SwingUtilities.invokeLater {
                Messages.showInfoMessage(project, stats, "Code Index")
            }
        }
    }
}
