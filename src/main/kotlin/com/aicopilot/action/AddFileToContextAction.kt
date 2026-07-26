package com.aicopilot.action

import com.aicopilot.service.ContextManager
import com.aicopilot.ui.AIChatPanel
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.vfs.VirtualFile
import javax.swing.JOptionPane

/**
 * Project 视图右键菜单 - 添加文件到 AI Context
 */
class AddFileToContextAction : AnAction() {
    
    private val logger = Logger.getInstance(AddFileToContextAction::class.java)
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        // 获取选中的文件
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (virtualFiles.isNullOrEmpty()) {
            JOptionPane.showMessageDialog(
                null,
                "No files selected.",
                "No Selection",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        // 获取上下文管理器并批量添加文件
        val contextManager = ContextManager.getInstance(project)
        val addedItems = contextManager.addFiles(virtualFiles.toList())
        
        if (addedItems.isEmpty()) {
            JOptionPane.showMessageDialog(
                null,
                "No supported files were added. Please select text or image files.",
                "Unsupported Files",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        // 显示 ToolWindow 并更新 UI
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI Copilot")
        toolWindow?.show {
            // 查找 AIChatPanel 并刷新上下文列表
            toolWindow.contentManager.contents
                .mapNotNull { it.component as? AIChatPanel }
                .firstOrNull()
                ?.refreshContextList()
        }
        
        val fileNames = addedItems.joinToString(", ") { it.displayName }
        logger.info("Added ${addedItems.size} files to AI context: $fileNames")
        
        // 显示成功提示
        JOptionPane.showMessageDialog(
            null,
            "Successfully added ${addedItems.size} file(s) to AI context:\n$fileNames",
            "Files Added",
            JOptionPane.INFORMATION_MESSAGE
        )
    }
    
    override fun update(e: AnActionEvent) {
        val virtualFiles = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = !virtualFiles.isNullOrEmpty()
    }
}
