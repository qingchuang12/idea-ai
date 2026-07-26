package com.aicopilot.ui

import com.aicopilot.model.AgentTask
import com.aicopilot.model.RecommendationKind
import com.aicopilot.model.TaskStatus
import com.aicopilot.model.TaskStep
import com.aicopilot.model.TaskStepStatus
import com.aicopilot.service.agent.AgentOrchestrator
import com.aicopilot.settings.ModelRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.JTextArea
import javax.swing.SwingUtilities

/**
 * Agent 任务看板独立视窗。
 * 左：待规划 | 中：执行中 | 右：已完成 三列看板；顶部进度与操作；底部实时日志与结果推荐卡片。
 */
class AgentBoardToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AgentTaskBoard(project)
        toolWindow.contentManager.factory
            .createContent(panel, "Agent Board", false)
            .also { toolWindow.contentManager.addContent(it) }
    }

    companion object {
        const val TOOL_WINDOW_ID = "AI Agent Board"

        /** 激活看板视窗并返回其面板实例。 */
        fun activate(project: Project): AgentTaskBoard? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return null
            tw.activate(null)
            return tw.contentManager.contents.mapNotNull { it.component as? AgentTaskBoard }.firstOrNull()
        }
    }
}

class AgentTaskBoard(private val project: Project) : JPanel(BorderLayout()) {

    private val orchestrator = AgentOrchestrator.getInstance(project)
    private val registry = ModelRegistry.getInstance()

    private val bgDark = JBColor(Color(0x1E, 0x1F, 0x22), Color(0x1E, 0x1F, 0x22))
    private val colBg = JBColor(Color(0x25, 0x27, 0x2B), Color(0x25, 0x27, 0x2B))
    private val accent = JBColor(Color(0x4F, 0x7C, 0xFF), Color(0x6E, 0x9B, 0xFF))
    private val textMain = JBColor(Color(0xE6, 0xE6, 0xE6), Color(0xE6, 0xE6, 0xE6))
    private val textSub = JBColor(Color(0xA8, 0xAB, 0xB2), Color(0xA8, 0xAB, 0xB2))

    private val goalLabel = JBLabel("暂无任务")
    private val statusLabel = JBLabel("")
    private val progressBar = JProgressBar(0, 100)
    private val confirmButton = JButton("确认并执行")
    private val cancelButton = JButton("取消")
    private val rollbackButton = JButton("回滚改动")

    private val pendingColumn = JPanel()
    private val runningColumn = JPanel()
    private val doneColumn = JPanel()

    private val logArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        background = bgDark
        foreground = textSub
        font = Font("JetBrains Mono", Font.PLAIN, 12)
    }
    private val recPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val listener: (AgentTask) -> Unit = { task ->
        SwingUtilities.invokeLater { render(task) }
    }

    init {
        background = bgDark
        border = JBUI.Borders.empty(12)
        add(buildHeader(), BorderLayout.NORTH)
        add(buildBoard(), BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)
        wireActions()
        orchestrator.addListener(listener)
        orchestrator.currentTask?.let { render(it) }
    }

    private fun buildHeader(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyBottom(12)
        val titleRow = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JBLabel("Agent 任务看板").apply {
                foreground = textMain
                font = font.deriveFont(Font.BOLD, 16f)
            })
            add(goalLabel.apply {
                foreground = textSub
                border = JBUI.Borders.emptyTop(4)
            })
        }
        add(titleRow, BorderLayout.WEST)

        val right = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusLabel.apply { foreground = accent; alignmentX = Component.RIGHT_ALIGNMENT })
            progressBar.apply {
                isStringPainted = true
                preferredSize = Dimension(200, 18)
                maximumSize = Dimension(200, 18)
                alignmentX = Component.RIGHT_ALIGNMENT
            }
            add(Box.createVerticalStrut(6))
            add(progressBar)
        }
        add(right, BorderLayout.EAST)
    }

    private fun buildBoard(): JComponent {
        val board = JPanel(GridLayout(1, 3, 12, 0)).apply { isOpaque = false }
        board.add(buildColumn("待规划", pendingColumn))
        board.add(buildColumn("执行中", runningColumn))
        board.add(buildColumn("已完成", doneColumn))
        return board
    }

    private fun buildColumn(title: String, content: JPanel): JComponent {
        content.apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }
        return JPanel(BorderLayout()).apply {
            background = colBg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(0x33, 0x36, 0x3C), Color(0x33, 0x36, 0x3C)), 1, true),
                JBUI.Borders.empty(8)
            )
            add(JBLabel(title).apply {
                foreground = textMain
                font = font.deriveFont(Font.BOLD, 13f)
                border = JBUI.Borders.emptyBottom(8)
            }, BorderLayout.NORTH)
            val scroll = JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
            }
            add(scroll, BorderLayout.CENTER)
        }
    }

    private fun buildFooter(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(12)
        preferredSize = Dimension(0, 220)

        val logScroll = JBScrollPane(logArea).apply {
            border = BorderFactory.createLineBorder(JBColor(Color(0x33, 0x36, 0x3C), Color(0x33, 0x36, 0x3C)), 1, true)
            preferredSize = Dimension(0, 130)
        }
        add(logScroll, BorderLayout.CENTER)

        val bottomBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            val recScroll = JBScrollPane(recPanel).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
                preferredSize = Dimension(0, 70)
            }
            add(recScroll, BorderLayout.CENTER)

            val buttons = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(6)
                add(rollbackButton)
                add(cancelButton)
                add(confirmButton)
            }
            add(buttons, BorderLayout.SOUTH)
        }
        add(bottomBar, BorderLayout.SOUTH)
    }

    private fun wireActions() {
        confirmButton.addActionListener {
            val model = registry.getActiveModel() ?: run {
                Messages.showWarningDialog(project, "请先在设置中配置并启用一个模型。", "无可用模型")
                return@addActionListener
            }
            orchestrator.startExecution(model) { title, detail ->
                var allow = false
                SwingUtilities.invokeAndWait {
                    allow = Messages.showYesNoDialog(
                        project, detail, title, "允许", "拒绝", Messages.getQuestionIcon()
                    ) == Messages.YES
                }
                allow
            }
        }
        cancelButton.addActionListener { orchestrator.cancel() }
        rollbackButton.addActionListener {
            if (orchestrator.rollback()) {
                Messages.showInfoMessage(project, "已回滚到执行前的快照。", "回滚成功")
            } else {
                Messages.showWarningDialog(project, "回滚失败或没有可用快照。", "回滚")
            }
        }
    }

    private fun render(task: AgentTask) {
        goalLabel.text = "目标：${task.goal.take(80)}"
        statusLabel.text = "状态：${statusText(task.status)}"
        progressBar.value = (task.progress() * 100).toInt()

        pendingColumn.removeAll()
        runningColumn.removeAll()
        doneColumn.removeAll()
        for (step in task.steps) {
            val card = buildStepCard(step)
            when (step.status) {
                TaskStepStatus.RUNNING -> runningColumn.add(card)
                TaskStepStatus.DONE, TaskStepStatus.FAILED -> doneColumn.add(card)
                else -> pendingColumn.add(card)
            }
            when (step.status) {
                TaskStepStatus.RUNNING -> runningColumn.add(Box.createVerticalStrut(8))
                TaskStepStatus.DONE, TaskStepStatus.FAILED -> doneColumn.add(Box.createVerticalStrut(8))
                else -> pendingColumn.add(Box.createVerticalStrut(8))
            }
        }
        pendingColumn.revalidate(); pendingColumn.repaint()
        runningColumn.revalidate(); runningColumn.repaint()
        doneColumn.revalidate(); doneColumn.repaint()

        logArea.text = task.log.joinToString("\n")
        logArea.caretPosition = logArea.document.length

        recPanel.removeAll()
        if (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED) {
            for (rec in task.recommendations) {
                recPanel.add(RecommendationCard(rec) { handleRecommendation(it.kind) })
                recPanel.add(Box.createVerticalStrut(6))
            }
        }
        recPanel.revalidate(); recPanel.repaint()

        confirmButton.isEnabled = task.status == TaskStatus.PLANNED
        cancelButton.isEnabled = task.status == TaskStatus.RUNNING || task.status == TaskStatus.PLANNED
        rollbackButton.isEnabled = task.snapshotLabel != null &&
            (task.status == TaskStatus.COMPLETED || task.status == TaskStatus.FAILED)
    }

    private fun buildStepCard(step: TaskStep): JComponent = JPanel(BorderLayout()).apply {
        background = JBColor(Color(0x2D, 0x2F, 0x34), Color(0x2D, 0x2F, 0x34))
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(statusColor(step.status), 1, true),
            JBUI.Borders.empty(8)
        )
        maximumSize = Dimension(Int.MAX_VALUE, 90)
        alignmentX = Component.LEFT_ALIGNMENT

        add(JBLabel("${statusIcon(step.status)}  ${step.role.display}").apply {
            foreground = accent
            font = font.deriveFont(Font.BOLD, 11f)
        }, BorderLayout.NORTH)
        add(JBLabel("<html><body style='width:150px'>${escape(step.title)}</body></html>").apply {
            foreground = textMain
            font = font.deriveFont(12f)
            border = JBUI.Borders.emptyTop(4)
        }, BorderLayout.CENTER)
    }

    private fun handleRecommendation(kind: RecommendationKind) {
        when (kind) {
            RecommendationKind.ROLLBACK -> rollbackButton.doClick()
            RecommendationKind.VIEW_DIFF -> {
                ToolWindowManager.getInstance(project).getToolWindow("Version Control")?.activate(null)
                    ?: ToolWindowManager.getInstance(project).getToolWindow("Commit")?.activate(null)
            }
            RecommendationKind.RUN_TESTS ->
                Messages.showInfoMessage(project, "请在工程中运行测试以验证本次改动。", "运行测试")
            else ->
                Messages.showInfoMessage(project, "已打开相关改动文件。", "提示")
        }
    }

    private fun statusText(s: TaskStatus): String = when (s) {
        TaskStatus.DRAFT -> "规划中"
        TaskStatus.PLANNED -> "待确认"
        TaskStatus.RUNNING -> "执行中"
        TaskStatus.COMPLETED -> "已完成"
        TaskStatus.FAILED -> "失败"
        TaskStatus.CANCELLED -> "已取消"
    }

    private fun statusIcon(s: TaskStepStatus): String = when (s) {
        TaskStepStatus.PENDING -> "○"
        TaskStepStatus.RUNNING -> "◐"
        TaskStepStatus.DONE -> "●"
        TaskStepStatus.FAILED -> "✕"
        TaskStepStatus.SKIPPED -> "—"
    }

    private fun statusColor(s: TaskStepStatus): JBColor = when (s) {
        TaskStepStatus.DONE -> JBColor(Color(0x3F, 0xB9, 0x50), Color(0x3F, 0xB9, 0x50))
        TaskStepStatus.FAILED -> JBColor(Color(0xF8, 0x51, 0x49), Color(0xF8, 0x51, 0x49))
        TaskStepStatus.RUNNING -> JBColor(Color(0x4F, 0x7C, 0xFF), Color(0x6E, 0x9B, 0xFF))
        else -> JBColor(Color(0x33, 0x36, 0x3C), Color(0x33, 0x36, 0x3C))
    }

    private fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
