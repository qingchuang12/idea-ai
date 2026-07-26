package com.aicopilot.service.completion

import com.aicopilot.service.ChatService
import com.aicopilot.service.understanding.CodeIndexService
import com.aicopilot.settings.AIApplicationSettings
import com.aicopilot.settings.ModelRegistry
import com.aicopilot.util.CodeFence
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.InlineCompletionSuggestion
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionTextElement
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.JBColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Color
import java.awt.Font

/**
 * 内联补全提供器（Phase 2 核心编码生产力）。
 *
 * 以光标左/右的轻量上下文窗口构造提示，并额外注入「代码库感知」上下文
 * （基于 [CodeIndexService] 检索当前光标处标识符在项目中相关的实现片段），
 * 向激活模型请求「光标之后应出现的代码」——既可单行，也可完整函数/代码块，
 * 以编辑器内联幽灵文本（灰色斜体）呈现；用户不接受时不留痕。
 */
@Suppress("DEPRECATION")
class AICodeCompletionProvider : InlineCompletionProvider {

    override val id: InlineCompletionProviderID = InlineCompletionProviderID("com.aicopilot.inline.completion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        if (!AIApplicationSettings.getInstance().enableInlineCompletion) return false
        return ModelRegistry.getInstance().getActiveModel() != null
    }

    override suspend fun getSuggestion(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val project = request.editor.project ?: return InlineCompletionSuggestion.empty()
        val (prefix, suffix) = readContext(request)
        if (prefix.isBlank()) return InlineCompletionSuggestion.empty()

        val completion = withContext(Dispatchers.IO) {
            runCatching { buildCompletion(project, prefix, suffix) }.getOrNull()
        } ?: return InlineCompletionSuggestion.empty()

        val trimmed = completion.trim().take(MAX_COMPLETION)
        if (trimmed.isBlank()) return InlineCompletionSuggestion.empty()

        return InlineCompletionSuggestion.withFlow {
            emit(InlineCompletionTextElement(trimmed, ghostTextAttributes()))
        }
    }

    private fun readContext(request: InlineCompletionRequest): Pair<String, String> =
        ReadAction.compute<Pair<String, String>, Throwable> {
        val document = request.document
        val offset = request.endOffset
        if (offset <= 0) return@compute "" to ""
        val start = (offset - PREFIX_WINDOW).coerceAtLeast(0)
        val end = (offset + SUFFIX_WINDOW).coerceAtMost(document.textLength)
        document.getText(TextRange(start, offset)) to document.getText(TextRange(offset, end))
    }

    private suspend fun buildCompletion(project: Project, prefix: String, suffix: String): String? {
        val model = ModelRegistry.getInstance().getActiveModel() ?: return null
        val codebase = codebaseContext(project, prefix)
        val system = buildString {
            append("You are an expert code completion engine inside an IDE. ")
            append("Generate the code that should appear at the cursor. ")
            append("You MUST produce a COMPLETE, compilable result: it may be a single line, a full function, or a multi-line block — choose the scope that best fits the surrounding context. ")
            append("Do NOT repeat the prefix or the suffix. Do NOT wrap the result in markdown fences, and do not add explanations or commentary. ")
            append("If no meaningful completion is appropriate, return an empty string.")
        }
        val user = buildString {
            if (codebase.isNotBlank()) {
                append("Related code from the project (use it for API/style consistency):\n")
                append(codebase)
                append("\n")
            }
            append("Code before cursor:\n```\n").append(prefix).append("\n```\n")
            append("Code after cursor (context only, do NOT regenerate):\n```\n").append(suffix).append("\n```\n")
            append("<CURSOR>")
        }
        val raw = ChatService().completeText(system, user, model).getOrNull() ?: return null
        // 模型偶尔仍会包裹围栏，做一次兜底清洗
        return CodeFence.extract(raw) ?: raw
    }

    /**
     * 代码库感知：从光标前缀提取最后标识符，检索索引命中的文件并读取其相关片段，
     * 让补全结果在 API/风格上与整个项目保持一致。
     */
    private fun codebaseContext(project: Project, prefix: String): String {
        val token = lastIdentifier(prefix)
        if (token.length < 3) return ""
        val hits = runCatching { CodeIndexService.getInstance(project).search(token, 4) }
            .getOrElse { emptyList<CodeIndexService.CodeIndexHit>() }
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        hits.take(2).forEach { hit ->
            val vf = LocalFileSystem.getInstance().findFileByPath(hit.filePath) ?: return@forEach
            val text = runCatching { String(vf.contentsToByteArray()) }.getOrNull() ?: return@forEach
            val idx = text.indexOf(token)
            if (idx >= 0) {
                val s = (idx - 200).coerceAtLeast(0)
                val e = (idx + 500).coerceAtMost(text.length)
                sb.append("// from ${hit.filePath}\n```\n").append(text.substring(s, e)).append("\n```\n\n")
            }
        }
        return sb.toString().trim()
    }

    private fun lastIdentifier(text: String): String {
        val m = Regex("[A-Za-z_][A-Za-z0-9_]*$").find(text) ?: return ""
        return m.value
    }

    private fun ghostTextAttributes(): TextAttributes {
        val attrs = TextAttributes()
        attrs.foregroundColor = JBColor(Color(0x8A8A8A), Color(0x8A8A8A))
        attrs.fontType = Font.ITALIC
        return attrs
    }

    companion object {
        private const val PREFIX_WINDOW = 2000
        private const val SUFFIX_WINDOW = 500
        private const val MAX_COMPLETION = 4000
    }
}
