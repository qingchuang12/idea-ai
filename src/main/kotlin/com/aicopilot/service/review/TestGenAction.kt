package com.aicopilot.service.review

import com.aicopilot.service.ChatService
import com.aicopilot.settings.ModelRegistry
import com.aicopilot.ui.AIChatPanel
import com.aicopilot.util.CodeFence
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import kotlinx.coroutines.runBlocking

/**
 * 单元测试生成 Action（Phase 2 生产力）。
 * 对当前编辑器中的文件，调用 LLM 生成完整测试文件并写入同目录（MVP 策略）。
 */
class TestGenAction : AnAction(
    "Generate Unit Tests",
    "Use AI to generate unit tests for the current file",
    null
) {
    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val psiFile = e.getData(CommonDataKeys.PSI_FILE)
        e.presentation.isEnabledAndVisible =
            project != null && editor != null && psiFile != null && ModelRegistry.getInstance().getActiveModel() != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val psiFile = e.getData(CommonDataKeys.PSI_FILE) ?: return
        val model = ModelRegistry.getInstance().getActiveModel() ?: run {
            Messages.showErrorDialog(project, "请先在设置中配置并启用一个模型。", "AI Test Gen")
            return
        }

        val language = psiFile.language.id
        val baseName = psiFile.name.substringBeforeLast(".")
        val source = psiFile.text
        val ext = if (language == "kotlin") "kt" else "java"

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "AI: Generating Tests", false) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.text = "Generating $baseName tests..."
                val raw = runBlocking {
                    ChatService().completeText(
                        systemPrompt = "You are a senior test engineer. Generate a complete, compilable unit test file in $language for the given source. " +
                            "Include appropriate imports, a test class, and meaningful test cases. " +
                            "Return ONLY the full test file inside a single fenced code block (```).",
                        userPrompt = "Source file name: $baseName\nLanguage: $language\n\nSource:\n```$language\n$source\n```",
                        model = model
                    ).getOrNull()
                } ?: return

                val code = CodeFence.extract(raw) ?: raw
                ApplicationManager.getApplication().invokeLater {
                    createTestFile(project, psiFile, baseName, ext, code)
                }
            }
        })
    }

    @Suppress("DEPRECATION")
    private fun createTestFile(project: Project, sourceFile: PsiFile, baseName: String, ext: String, code: String) {
        val fileName = "${baseName}Test.$ext"
        val dir: PsiDirectory = sourceFile.parent ?: run {
            Messages.showErrorDialog(project, "无法确定写入目录。", "AI Test Gen")
            return
        }
        WriteCommandAction.runWriteCommandAction(project) {
            dir.findFile(fileName)?.delete()
            val psiFile = PsiFileFactory.getInstance(project).createFileFromText(fileName, code)
            dir.add(psiFile)
        }
        val vf = dir.virtualFile.findChild(fileName)
        if (vf != null) FileEditorManager.getInstance(project).openFile(vf, true)
        else AIChatPanel.find(project)?.showGeneratedResult("Unit Tests", "已生成测试文件 `$fileName`（写入 ${dir.virtualFile.path}）。")
    }
}
