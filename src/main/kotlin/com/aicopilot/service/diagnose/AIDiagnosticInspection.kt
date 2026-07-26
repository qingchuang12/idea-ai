package com.aicopilot.service.diagnose

import com.aicopilot.service.ChatService
import com.aicopilot.settings.ModelRegistry
import com.aicopilot.util.CodeFence
import com.intellij.codeInspection.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import kotlinx.coroutines.runBlocking

/**
 * AI 智能诊断 Inspection（Phase 2 生产力）。
 *
 * 以轻量文本启发式在编辑器中内联标注常见问题（如 printStackTrace、空的 catch 块、
 * 遗留 TODO/FIXME），并提供一个"用 Copilot 一键修复"的 LocalQuickFix——
 * 该 QuickFix 在后台调用 LLM 生成修复代码并写回文档。
 */
class AIDiagnosticInspection : LocalInspectionTool() {

    override fun getDisplayName(): String = "AI Smart Diagnostic"
    override fun getShortName(): String = "AISmartDiagnostic"
    override fun getGroupDisplayName(): String = "AI Copilot"
    override fun isEnabledByDefault(): Boolean = true

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor> {
        if (ModelRegistry.getInstance().getActiveModel() == null) return emptyArray()
        if (file.language.id !in setOf("JAVA", "kotlin")) return emptyArray()

        val document = file.viewProvider.document ?: return emptyArray()
        val descriptors = mutableListOf<ProblemDescriptor>()

        ReadAction.compute<Unit, Throwable> {
            val text = document.text
            val totalLines = document.lineCount
            for (i in 0 until totalLines) {
                val lineStart = document.getLineStartOffset(i)
                val lineEnd = document.getLineEndOffset(i)
                val line = document.charsSequence.subSequence(lineStart, lineEnd).toString()

                when {
                    "printStackTrace()" in line -> {
                        descriptors.add(
                            manager.createProblemDescriptor(
                                file,
                                com.intellij.openapi.util.TextRange(lineStart, lineEnd),
                                "AI: 直接调用 printStackTrace() 不利于日志聚合，建议改用日志框架。",
                                ProblemHighlightType.WEAK_WARNING,
                                isOnTheFly,
                                FixWithAI(line.trim(), "将 printStackTrace() 替换为合适的日志记录")
                            )
                        )
                    }
                    line.contains("//") && (line.contains("TODO") || line.contains("FIXME")) -> {
                        descriptors.add(
                            manager.createProblemDescriptor(
                                file,
                                com.intellij.openapi.util.TextRange(lineStart, lineEnd),
                                "AI: 遗留的 TODO/FIXME 待处理。",
                                ProblemHighlightType.WEAK_WARNING,
                                isOnTheFly,
                                FixWithAI(line.trim(), "实现或处理该 TODO/FIXME 标注处的逻辑")
                            )
                        )
                    }
                    line.trim().matches(Regex("""catch\s*\([^)]*\)\s*\{\s*\}""")) -> {
                        descriptors.add(
                            manager.createProblemDescriptor(
                                file,
                                com.intellij.openapi.util.TextRange(lineStart, lineEnd),
                                "AI: 空的 catch 块会吞掉异常，建议记录日志或重新抛出。",
                                ProblemHighlightType.WEAK_WARNING,
                                isOnTheFly,
                                FixWithAI(line.trim(), "为空的 catch 块补充异常处理逻辑")
                            )
                        )
                    }
                }
            }
        }
        return descriptors.toTypedArray()
    }

    /**
     * 一键修复：后台调用 LLM 生成修正代码并写回文档中精确匹配的原片段位置。
     */
    class FixWithAI(
        private val snippet: String,
        private val issue: String
    ) : LocalQuickFix {
        override fun getFamilyName(): String = "AI Fix"
        override fun getName(): String = "AI: 用 Copilot 修复"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val model = ModelRegistry.getInstance().getActiveModel()
            if (model == null) {
                Messages.showErrorDialog(project, "请先在设置中配置并启用一个模型。", "AI Fix")
                return
            }
            val file = descriptor.psiElement?.containingFile ?: return
            ApplicationManager.getApplication().executeOnPooledThread {
                val raw = runBlocking {
                    ChatService().completeText(
                        systemPrompt = buildString {
                            append("You are a senior developer. Fix the described issue in the given code snippet. ")
                            append("Return ONLY the corrected code inside a single fenced code block (```). No explanations.")
                        },
                        userPrompt = "Issue: $issue\n\nCode:\n```\n$snippet\n```",
                        model = model
                    ).getOrNull()
                } ?: return@executeOnPooledThread

                val fixed = CodeFence.extract(raw) ?: raw
                val doc = file.viewProvider.document as? Document ?: return@executeOnPooledThread
                val idx = doc.text.indexOf(snippet)
                if (idx < 0) return@executeOnPooledThread
                ApplicationManager.getApplication().invokeLater {
                    WriteCommandAction.runWriteCommandAction(project) {
                        doc.replaceString(idx, idx + snippet.length, fixed)
                    }
                }
            }
        }
    }
}
