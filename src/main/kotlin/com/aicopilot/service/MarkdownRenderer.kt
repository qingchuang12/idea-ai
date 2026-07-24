package com.aicopilot.service

import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.commonmark.ext.gfm.tables.TablesExtension
import java.util.*

/**
 * Markdown 渲染服务
 * 将 Markdown 转换为 HTML 用于展示
 */
class MarkdownRenderer {
    
    private val parser: Parser
    private val renderer: HtmlRenderer
    
    init {
        // 配置 CommonMark 支持 GFM 表格扩展
        val extensions = listOf(TablesExtension.create())
        
        parser = Parser.builder()
            .extensions(extensions)
            .build()
        
        renderer = HtmlRenderer.builder()
            .extensions(extensions)
            .build()
    }
    
    /**
     * 将 Markdown 渲染为 HTML
     */
    fun renderToHtml(markdown: String): String {
        return try {
            val document: Node = parser.parse(markdown)
            renderer.render(document)
        } catch (e: Exception) {
            // 解析失败时返回原始文本
            "<pre>${escapeHtml(markdown)}</pre>"
        }
    }
    
    /**
     * 转义 HTML 特殊字符
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }
    
    companion object {
        private val instance = MarkdownRenderer()
        
        fun getInstance(): MarkdownRenderer = instance
    }
}
