package com.aicopilot.action

import com.aicopilot.service.ChatService
import com.aicopilot.settings.AIApplicationSettings
import com.aicopilot.settings.ModelRegistry
import com.aicopilot.util.CodeFence
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 行间预测（NES - Next Edit Suggestion）。
 *
 * 在编辑器中触发（快捷键），基于当前光标处的代码上下文，让模型预测
 * 「开发者最可能进行的下一步编辑」，并以气球提示展示该补全片段；
 * 用户可一键「应用」插入到光标处，或「忽略」关闭。
 */
class AINesAction : AnAction() {

    private var activePopup: JBPopup? = null

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            showMessage(project, "请在一个打开的编辑器中触发行间预测。")
            return
        }
        val model = ModelRegistry.getInstance().getActiveModel() ?: run {
            showMessage(project, "请先在设置中配置并启用一个模型。")
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val code = runBlocking { predict(project, editor, model) }
            SwingUtilities.invokeLater {
                if (code == null) {
                    showMessage(project, "暂无可预测的下一步编辑。")
                } else {
                    showSuggestion(editor, code, project)
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible =
            editor != null &&
            AIApplicationSettings.getInstance().enableNes &&
            ModelRegistry.getInstance().getActiveModel() != null
    }

    private suspend fun predict(project: Project, editor: Editor, model: com.aicopilot.model.ModelConfig): String? {
        val vf = editor.virtualFile ?: return null
        val caret = editor.caretModel.offset
        val text = editor.document.charsSequence.toString()
        val start = (caret - 2000).coerceAtLeast(0)
        val end = (caret + 600).coerceAtMost(text.length)
        val before = text.substring(start, caret)
        val after = text.substring(caret, end)
        if (before.isBlank()) return null

        val system = buildString {
            append("You are a Next-Edit-Suggestion engine inside an IDE. ")
            append("Given the code and a <CURSOR> marker, predict the single most likely next edit the developer will make. ")
            append("Output ONLY the code to insert at the cursor — it may be one line, a block, or a full function stub. ")
            append("Do NOT wrap in markdown fences, and do not add explanations.")
        }
        val user = buildString {
            append("Language: ${vf.extension ?: "text"}\n")
            append("Code before cursor:\n```\n").append(before).append("\n```\n")
            append("Code after cursor (context only):\n```\n").append(after).append("\n```\n")
            append("<CURSOR>")
        }
        val raw = withContext(Dispatchers.IO) {
            ChatService().completeText(system, user, model).getOrNull()
        } ?: return null
        val code = CodeFence.extract(raw) ?: raw
        return code.takeIf { it.isNotBlank() }
    }

    private fun showSuggestion(editor: Editor, code: String, project: Project) {
        activePopup?.cancel()
        val area = JBTextArea(code, 8, 60).apply {
            isEditable = false
            font = java.awt.Font("Monospaced", java.awt.Font.PLAIN, 12)
        }
        val applyBtn = JButton("应用").apply {
            addActionListener {
                applyEdit(editor, code, project)
                activePopup?.cancel()
            }
        }
        val dismissBtn = JButton("忽略").apply {
            addActionListener { activePopup?.cancel() }
        }
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JLabel("Next Edit Suggestion（行间预测）："), BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
            add(JPanel().apply { add(applyBtn); add(dismissBtn) }, BorderLayout.SOUTH)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, null)
            .setTitle("AI Next Edit")
            .setResizable(true)
            .createPopup()
        activePopup = popup
        popup.showInBestPositionFor(editor)
    }

    private fun applyEdit(editor: Editor, code: String, project: Project) {
        val caret = editor.caretModel.offset
        WriteCommandAction.runWriteCommandAction(project) {
            editor.document.insertString(caret, code)
            editor.caretModel.moveToOffset(caret + code.length)
        }
    }

    private fun showMessage(project: Project, msg: String) {
        val label = JLabel(msg)
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(label, null)
            .setTitle("AI Next Edit")
            .createPopup()
        activePopup = popup
        // 显示在编辑器或状态栏；简单起见用轻量气球固定显示
        val frame = javax.swing.SwingUtilities.getWindowAncestor(
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("AI Copilot")?.component ?: return
        )
        if (frame != null) popup.showInCenterOf(frame) else popup.showInFocusCenter()
    }
}
