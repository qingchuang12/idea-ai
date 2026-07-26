package com.aicopilot.service.agent

import com.aicopilot.service.agent.ToolUtils.stringArg
import com.google.gson.JsonObject
import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiMethod
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.PsiShortNamesCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 语义符号检索：根据名称查找类 / 方法（基于 PSI 短名缓存）。
 */
class SymbolLookupTool : AITool {
    override val name = "symbol_lookup"
    override val description = "根据符号名称查找工程中的类或方法定义（返回全限定名与所在文件）。"
    override val parameters = ToolSchema.obj(
        "name" to ToolSchema.string("类名或方法名（短名）"),
        required = listOf("name")
    )

    override suspend fun execute(args: JsonObject, ctx: ToolContext): ToolResult = withContext(Dispatchers.Default) {
        val symbol = args.stringArg("name")?.takeIf { it.isNotBlank() }
            ?: return@withContext ToolResult.error("missing 'name'")
        try {
            val output = ReadAction.compute<String, RuntimeException> {
                val project = ctx.project
                val scope = GlobalSearchScope.projectScope(project)
                val cache = PsiShortNamesCache.getInstance(project)
                val sb = StringBuilder()

                val classes: Array<PsiClass> = cache.getClassesByName(symbol, scope)
                if (classes.isNotEmpty()) {
                    sb.append("Classes:\n")
                    classes.take(20).forEach { cls ->
                        val file = cls.containingFile?.virtualFile?.path ?: "?"
                        sb.append("  ${cls.qualifiedName ?: cls.name}  ($file)\n")
                    }
                }

                val methods: Array<PsiMethod> = cache.getMethodsByName(symbol, scope)
                if (methods.isNotEmpty()) {
                    sb.append("Methods:\n")
                    methods.take(20).forEach { m ->
                        val owner = m.containingClass?.qualifiedName ?: "?"
                        val file = m.containingFile?.virtualFile?.path ?: "?"
                        sb.append("  $owner#${m.name}(${m.parameterList.parametersCount})  ($file)\n")
                    }
                }

                if (sb.isEmpty()) "No symbol named '$symbol' found." else sb.toString()
            }
            ToolResult.ok(output)
        } catch (e: Exception) {
            ToolResult.error(e.message ?: "symbol lookup failed")
        }
    }
}
