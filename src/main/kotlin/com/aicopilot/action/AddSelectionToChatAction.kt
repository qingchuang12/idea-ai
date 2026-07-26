package com.aicopilot.action

import com.aicopilot.model.ContextItem
import com.aicopilot.model.ContextType
import com.aicopilot.service.ContextManager
import com.aicopilot.ui.AIChatPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindowManager
import javax.swing.JOptionPane

/**
 * 编辑器右键菜单 - 添加选区到 AI Chat
 */
class AddSelectionToChatAction : AnAction() {
    
    private val logger = Logger.getInstance(AddSelectionToChatAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        // 获取选中的文本
        val selectionModel = editor.selectionModel
        if (!selectionModel.hasSelection()) {
            JOptionPane.showMessageDialog(
                null,
                "Please select some text first.",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        val selectedText = selectionModel.selectedText ?: return
        
        // 获取上下文管理器并添加选区
        val contextManager = ContextManager.getInstance(project)
        val contextItem = contextManager.addSelection(
            text = selectedText,
            description = "Selection from ${editor.virtualFile?.name ?: "Editor"}"
        )
        
        // 显示 ToolWindow 并更新 UI
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI Copilot")
        toolWindow?.show {
            // 查找 AIChatPanel 并刷新上下文列表
            val panel = toolWindow.contentManager.contents
                .mapNotNull { it.component as? AIChatPanel }
                .firstOrNull()
            panel?.let {
                it.addContextItem(contextItem)
                it.refreshContextList()
            }
        }
        
        logger.info("Added selection to AI chat context: ${selectedText.length} characters")
    }
    
    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.selectionModel.hasSelection()
    }
}
