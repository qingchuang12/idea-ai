package com.aicopilot.action

import com.aicopilot.ecosystem.RulesLoader
import com.aicopilot.ui.AIKnowledgePanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import javax.swing.SwingUtilities

/**
 * 重新加载工程规则 .rules（Phase 6）。
 */
class ReloadRulesAction : AnAction(
    "Reload Project Rules",
    "Re-scan .rules files in the project",
    null
) {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val loader = RulesLoader.getInstance(project)
        loader.refresh()
        SwingUtilities.invokeLater {
            Messages.showInfoMessage(
                project,
                "已重新加载 ${loader.count()} 条项目规则。",
                "Project Rules"
            )
            AIKnowledgePanel.activate(project)
        }
    }
}
