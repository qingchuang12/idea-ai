package com.aicopilot.settings

import com.aicopilot.model.ModelConfig
import com.aicopilot.model.ModelSource
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * 声明式模型配置加载器。
 *
 * 支持双路径：
 *  - 项目级：{projectBasePath}/models.json 或 {projectBasePath}/.idea-ai/models.json
 *  - 用户级：{userHome}/.idea-ai/models.json
 *
 * models.json 结构示例：
 * ```json
 * {
 *   "models": [
 *     {
 *       "alias": "GPT-4o",
 *       "provider": "OpenAI",
 *       "baseUrl": "https://api.openai.com/v1",
 *       "apiKey": "sk-xxx",
 *       "modelName": "gpt-4o",
 *       "protocol": "FUNCTION_CALLING",
 *       "supportsFunctionCalling": true,
 *       "supportsVision": true,
 *       "headers": { "X-Custom": "value" }
 *     }
 *   ]
 * }
 * ```
 * 也兼容顶层直接为数组的写法：`[ {..}, {..} ]`
 */
object ModelsJsonLoader {

    private val logger = Logger.getInstance(ModelsJsonLoader::class.java)

    const val FILE_NAME = "models.json"

    fun load(project: Project?): List<ModelConfig> {
        val result = mutableListOf<ModelConfig>()

        // 用户级
        val userFile = File(System.getProperty("user.home"), ".idea-ai/$FILE_NAME")
        result += parseFile(userFile, ModelSource.USER_JSON)

        // 项目级（优先级更高，放后面便于按需覆盖显示）
        val basePath = project?.basePath
        if (basePath != null) {
            val candidates = listOf(
                File(basePath, FILE_NAME),
                File(basePath, ".idea-ai/$FILE_NAME")
            )
            for (f in candidates) {
                result += parseFile(f, ModelSource.PROJECT_JSON)
            }
        }
        return result
    }

    private fun parseFile(file: File, source: ModelSource): List<ModelConfig> {
        if (!file.exists() || !file.isFile) return emptyList()
        return try {
            val text = file.readText()
            val root = JsonParser.parseString(text)
            val array: JsonArray = when {
                root.isJsonArray -> root.asJsonArray
                root.isJsonObject && root.asJsonObject.has("models") ->
                    root.asJsonObject.getAsJsonArray("models")
                else -> return emptyList()
            }
            array.mapNotNull { el ->
                if (!el.isJsonObject) return@mapNotNull null
                parseModel(el.asJsonObject, source)
            }
        } catch (e: Exception) {
            logger.warn("Failed to parse models.json at ${file.path}: ${e.message}")
            emptyList()
        }
    }

    private fun parseModel(obj: JsonObject, source: ModelSource): ModelConfig {
        fun str(key: String, default: String = ""): String =
            if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asString else default

        fun bool(key: String, default: Boolean = false): Boolean =
            if (obj.has(key) && !obj.get(key).isJsonNull) obj.get(key).asBoolean else default

        val headers = if (obj.has("headers") && obj.get("headers").isJsonObject)
            obj.getAsJsonObject("headers").toString() else "{}"

        val alias = str("alias", str("modelName", "Unnamed"))
        // 用来源路径 + alias 生成稳定 id，避免每次加载 id 变化
        val stableId = "${source.name.lowercase()}:$alias"

        return ModelConfig(
            id = stableId,
            alias = alias,
            provider = str("provider", "Custom"),
            baseUrl = str("baseUrl", "https://api.openai.com/v1"),
            apiKey = str("apiKey"),
            modelName = str("modelName", alias),
            headersJson = headers,
            bodyTemplate = str("bodyTemplate", ModelConfig.DEFAULT_BODY_TEMPLATE),
            protocol = str("protocol", "OPENAI_COMPAT").uppercase(),
            supportsFunctionCalling = bool("supportsFunctionCalling"),
            supportsVision = bool("supportsVision"),
            enabled = bool("enabled", true),
            source = source.name
        )
    }
}
