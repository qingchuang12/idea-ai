package com.aicopilot.service.agent

import com.google.gson.JsonObject
import java.io.File

/**
 * 工具通用辅助方法。
 */
object ToolUtils {

    /** 解析路径：绝对路径直接使用，相对路径基于工程根目录。 */
    fun resolveFile(basePath: String?, path: String): File? {
        val f = File(path)
        return if (f.isAbsolute) f
        else if (basePath != null) File(basePath, path)
        else null
    }

    fun JsonObject.stringArg(key: String): String? =
        if (has(key) && !get(key).isJsonNull) get(key).asString else null

    fun JsonObject.intArg(key: String, default: Int): Int =
        try { if (has(key) && !get(key).isJsonNull) get(key).asInt else default } catch (e: Exception) { default }

    /** 需要跳过的目录 */
    val SKIP_DIRS = setOf(".git", ".idea", "build", "out", "target", "node_modules", ".gradle", "dist", ".codebuddy")

    /** 需要跳过的二进制扩展名 */
    val BINARY_EXTS = setOf(
        "class", "jar", "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico",
        "zip", "gz", "tar", "exe", "dll", "so", "bin", "pdf", "lock"
    )
}
