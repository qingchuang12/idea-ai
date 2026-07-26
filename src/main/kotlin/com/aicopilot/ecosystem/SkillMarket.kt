package com.aicopilot.ecosystem

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import java.io.File

/**
 * 技能库（应用级，本地，手动添加）。
 *
 * Phase 6「开放生态」的技能层，遵循 skill-creator 方法论：
 * 每个技能是一个目录，含一个 `SKILL.md`（YAML frontmatter: name/description + Markdown 指令），
 * 可选 `scripts/`、`references/`、`assets/`。技能库扫描用户级（~/.ai-skills）与项目级（<project>/.ai-skills）
 * 技能目录并注册，供 Agent 工具（skill_list / skill_run）按需加载为系统指令。
 *
 * 不做联网“市场”下载：用户通过 [addSkill] 手动创建技能（写入 ~/.ai-skills），内置若干常用技能作为可运行 MVP。
 */
@Service(Service.Level.APP)
class SkillMarket {

    data class Skill(
        val name: String,
        val description: String,
        val instructions: String,
        val source: String,
        val hasScripts: Boolean = false,
        val hasReferences: Boolean = false,
        val hasAssets: Boolean = false
    )

    private val skills = LinkedHashMap<String, Skill>()

    init {
        registerBuiltins()
        refresh()
    }

    fun refresh() {
        val dirs = scanDirs()
        for (dir in dirs) {
            dir.listFiles { f -> f.isDirectory }?.forEach { skillDir ->
                loadSkillDir(skillDir)?.let { skills[it.name] = it }
            }
        }
        thisLogger().info("SkillMarket: registered ${skills.size} skill(s)")
    }

    private fun scanDirs(): List<File> {
        val result = mutableListOf<File>()
        // 用户级
        val userDir = File(System.getProperty("user.home"), ".ai-skills")
        if (userDir.exists()) result.add(userDir)
        // 项目级（需工程根，这里提供可选扫描：由 project 服务调用 registerProjectSkills）
        return result
    }

    /**
     * 由项目级代码额外注册项目技能目录（<project>/.ai-skills）。
     */
    fun registerProjectSkills(projectRoot: String) {
        val dir = File(projectRoot, ".ai-skills")
        if (!dir.exists()) return
        dir.listFiles { f -> f.isDirectory }?.forEach { skillDir ->
            loadSkillDir(skillDir)?.let { skills[it.name] = it }
        }
    }

    private fun loadSkillDir(dir: File): Skill? {
        val md = File(dir, "SKILL.md")
        if (!md.exists()) return null
        val text = runCatching { md.readText(Charsets.UTF_8) }.getOrElse { return null }
        val (name, description) = parseFrontmatter(text, dir.name)
        if (name.isBlank()) return null
        val body = stripFrontmatter(text).trim()
        val scripts = File(dir, "scripts").exists()
        val refs = File(dir, "references").exists()
        val assets = File(dir, "assets").exists()
        return Skill(name, description, body, dir.absolutePath, scripts, refs, assets)
    }

    private fun parseFrontmatter(text: String, fallbackName: String): Pair<String, String> {
        if (!text.startsWith("---")) return fallbackName to ""
        val end = text.indexOf("\n---", 3)
        if (end < 0) return fallbackName to ""
        val fm = text.substring(3, end)
        var name = ""
        var desc = ""
        fm.lines().forEach { line ->
            val idx = line.indexOfFirst { it == ':' }
            if (idx > 0) {
                val key = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim().trim('"', '\'')
                when (key) {
                    "name" -> name = value
                    "description" -> desc = value
                }
            }
        }
        return (name.ifBlank { fallbackName }) to desc
    }

    private fun stripFrontmatter(text: String): String {
        if (!text.startsWith("---")) return text
        val end = text.indexOf("\n---", 3)
        if (end < 0) return text
        return text.substring(end + 4)
    }

    fun getAllSkills(): List<Skill> = skills.values.toList()
    fun getSkill(name: String): Skill? = skills[name]
    fun count(): Int = skills.size

    /** 将技能指令渲染为可注入的提示文本 */
    fun renderAsPrompt(name: String): String {
        val s = skills[name] ?: return "skill not found: $name"
        return buildString {
            appendLine("# Skill: ${s.name}")
            if (s.description.isNotBlank()) appendLine("> ${s.description}")
            appendLine()
            appendLine(s.instructions)
        }
    }

    /**
     * 手动添加技能：在用户级目录 ~/.ai-skills/<name>/SKILL.md 写入 frontmatter + 指令，并立即注册。
     * 返回是否成功（失败通常因无法写入用户主目录）。
     */
    fun addSkill(name: String, description: String, instructions: String): Boolean {
        val safe = name.trim().replace(Regex("[^A-Za-z0-9_\\-]"), "_").ifBlank { return false }
        val skillDir = File(File(System.getProperty("user.home"), ".ai-skills"), safe).apply { mkdirs() }
        val md = File(skillDir, "SKILL.md")
        val content = buildString {
            appendLine("---")
            appendLine("name: $safe")
            appendLine("description: ${description.trim()}")
            appendLine("---")
            appendLine()
            appendLine(instructions.trim())
        }
        return runCatching {
            md.writeText(content, Charsets.UTF_8)
            refresh()
            true
        }.getOrElse { false }
    }

    private fun registerBuiltins() {
        skills["security-audit"] = Skill(
            name = "security-audit",
            description = "This skill should be used when reviewing code for security vulnerabilities such as injection, XSS, insecure deserialization, and secret leakage.",
            instructions = """
                Perform a security audit of the provided code.
                - Identify OWASP Top-10 style issues: SQL/command injection, XSS, path traversal, insecure deserialization, hardcoded secrets, weak cryptography.
                - For each finding, report: file:line, severity, description, and a concrete remediation.
                - If no issue is found, state the code is clean with a short justification.
            """.trimIndent(),
            source = "builtin"
        )
        skills["perf-review"] = Skill(
            name = "perf-review",
            description = "This skill should be used when analyzing code for performance bottlenecks such as unnecessary allocations, N+1 queries, blocking I/O on UI threads, and redundant computation.",
            instructions = """
                Review the provided code for performance issues.
                - Flag hot-path allocations, nested loops with quadratic complexity, synchronous I/O on the EDT, and missing caching.
                - For each finding, give the location, impact, and a recommended fix with a short code sketch.
            """.trimIndent(),
            source = "builtin"
        )
        skills["db-modeling"] = Skill(
            name = "db-modeling",
            description = "This skill should be used when designing or reverse-engineering database schemas, entities, and ORM mappings from requirements or existing code.",
            instructions = """
                Design or reverse-model a database schema from the given requirement or code.
                - Produce normalized tables with primary/foreign keys, indexes for query patterns, and relationships.
                - Map tables to language entity classes and suggest ORM annotations.
                - Call out trade-offs (denormalization, sharding) when relevant.
            """.trimIndent(),
            source = "builtin"
        )
        skills["code-explainer"] = Skill(
            name = "code-explainer",
            description = "This skill should be used when a user asks to explain what a piece of code does, how it works, or why it is structured a certain way.",
            instructions = """
                Explain the provided code clearly for its audience.
                - Summarize the purpose in one sentence, then walk through control flow and key abstractions.
                - Highlight non-obvious decisions, side effects, and potential pitfalls.
                - Use diagrams in text (ASCII/Markdown) only when they aid understanding.
            """.trimIndent(),
            source = "builtin"
        )
    }

    companion object {
        fun getInstance(): SkillMarket =
            ApplicationManager.getApplication().getService(SkillMarket::class.java)
    }
}
