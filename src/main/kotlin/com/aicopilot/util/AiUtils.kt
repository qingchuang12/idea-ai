package com.aicopilot.util

/**
 * 代码围栏（fenced code block）解析工具，供各项生产力能力复用。
 */
object CodeFence {
    private val FENCE = Regex("```[a-zA-Z0-9]*\\s*\\n([\\s\\S]*?)```")

    /** 提取第一个围栏代码块内容；无则返回 null。 */
    fun extract(text: String): String? {
        val m = FENCE.find(text) ?: return null
        return m.groupValues[1].trim()
    }

    /** 提取围栏代码；无围栏时回退为去掉首尾空白的原始文本。 */
    fun extractOrRaw(text: String): String {
        return extract(text) ?: text.trim()
    }
}
