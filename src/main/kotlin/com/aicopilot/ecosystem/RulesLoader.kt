package com.aicopilot.ecosystem

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 项目规则加载器（项目级）。
 *
 * Phase 6「开放生态」的规则层：扫描工程中的 `.rules` 目录与 `.rules` 文件，
 * 将内容作为编码规范 / 约束注入到 Agent 系统提示与每次对话的上下文。
 * 支持热重载（Reload Rules 动作）。
 */
@Service(Service.Level.PROJECT)
class RulesLoader(private val project: Project) {

    data class Rule(
        val name: String,
        val source: String,
        val content: String
    )

    private val rules = mutableListOf<Rule>()

    init {
        refresh()
    }

    fun refresh() {
        rules.clear()
        val roots = ProjectRootManager.getInstance(project).contentRoots
        for (root in roots) {
            // 1) .rules 目录下的所有文件
            val rulesDir = root.findChild(".rules")
            if (rulesDir != null && rulesDir.isDirectory) {
                VfsUtilCore.iterateChildrenRecursively(rulesDir, null) { f ->
                    if (!f.isDirectory) loadRuleFile(f, ".rules/${f.path.removePrefix(rulesDir.path)}")
                    true
                }
            }
            // 2) 工程根目录下 *.rules 文件（如 project.rules / team.rules）
            VfsUtilCore.iterateChildrenRecursively(root, null) { f ->
                if (!f.isDirectory && f.name.endsWith(".rules") && f != rulesDir) {
                    loadRuleFile(f, f.name)
                }
                true
            }
        }
        thisLogger().info("RulesLoader: loaded ${rules.size} rule(s)")
    }

    private fun loadRuleFile(file: VirtualFile, name: String) {
        runCatching {
            val content = String(file.contentsToByteArray(), StandardCharsets.UTF_8)
            if (content.isNotBlank()) {
                rules.add(Rule(name = name, source = file.path, content = content))
            }
        }.onFailure { thisLogger().warn("failed to load rule: ${file.path}", it) }
    }

    fun listRules(): List<Rule> = rules.toList()

    fun count(): Int = rules.size

    /** 在项目 `.rules/` 目录新建一条规则（写入磁盘并热重载）。 */
    fun addRule(name: String, content: String): Boolean {
        val root = ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: return false
        val dir = File(root.path, ".rules").apply { mkdirs() }
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_\\-.]"), "_")
        val fileName = if (safe.endsWith(".rules")) safe else "$safe.rules"
        val f = File(dir, fileName)
        return runCatching {
            f.writeText(content, StandardCharsets.UTF_8)
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            refresh()
            true
        }.getOrElse { thisLogger().warn("addRule failed: $fileName", it); false }
    }

    /** 删除一条规则文件（按 source 路径定位）。 */
    fun deleteRule(rule: Rule): Boolean {
        val f = File(rule.source)
        return runCatching {
            if (f.exists()) f.delete()
            LocalFileSystem.getInstance().refreshAndFindFileByIoFile(f)
            refresh()
            true
        }.getOrElse { thisLogger().warn("deleteRule failed: ${rule.source}", it); false }
    }

    /** 汇总为系统提示文本 */
    fun asSystemPrompt(): String {
        if (rules.isEmpty()) return ""
        return buildString {
            appendLine("# Project Rules（项目规则，必须遵循）")
            rules.forEachIndexed { i, r ->
                appendLine("## Rule ${i + 1}: ${r.name}")
                appendLine(r.content.trim())
                appendLine()
            }
        }
    }

    companion object {
        fun getInstance(project: Project): RulesLoader =
            project.getService(RulesLoader::class.java)
    }
}
