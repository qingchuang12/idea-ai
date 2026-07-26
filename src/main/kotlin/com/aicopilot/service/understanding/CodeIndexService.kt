package com.aicopilot.service.understanding

import com.aicopilot.service.agent.ToolUtils.SKIP_DIRS
import com.aicopilot.service.agent.ToolUtils.BINARY_EXTS
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 代码库索引服务（项目级）。
 *
 * Phase 5「深度工程理解」的起步实现：基于关键词的倒排索引 + 文件元信息。
 * 首次构建在后台任务中进行，避免阻塞 UI；之后提供 [search] 做相关文件检索，
 * 供 Agent 工具（code_index_search）与「AI Knowledge」视窗复用。
 *
 * 后续可平滑升级为 PSI 符号级索引（类/方法/字段）而对外接口不变。
 */
@Service(Service.Level.PROJECT)
class CodeIndexService(private val project: Project) {

    /** term -> 命中的文件路径集合（大小写不敏感） */
    private val inverted = ConcurrentHashMap<String, MutableSet<String>>()

    /** 文件路径 -> 元信息 */
    private val meta = ConcurrentHashMap<String, FileMeta>()

    @Volatile
    private var indexedFileCount = 0

    @Volatile
    private var lastDurationMs = 0L

    @Volatile
    var lastQuery: String = ""
        private set

    data class FileMeta(
        val path: String,
        val language: String,
        val size: Int
    )

    data class CodeIndexHit(
        val filePath: String,
        val score: Int,
        val language: String
    )

    /** 是否已构建过索引 */
    fun isIndexed(): Boolean = indexedFileCount > 0

    fun stats(): String =
        "已索引 $indexedFileCount 个文件，词典规模 ${inverted.size} 词，上次耗时 ${lastDurationMs}ms"

    /**
     * 异步构建索引（后台任务，可取消）。
     */
    fun indexAsync(onDone: (String) -> Unit = {}) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "AI: Indexing codebase", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                val t0 = System.currentTimeMillis()
                indicator.isIndeterminate = false
                val roots = ProjectRootManager.getInstance(project).contentRoots
                val visited = AtomicInteger(0)
                var total = 0
                for (root in roots) {
                    if (indicator.isCanceled) break
                    total += countFiles(root)
                }
                for (root in roots) {
                    if (indicator.isCanceled) break
                    VfsUtilCore.iterateChildrenRecursively(root, null) { file ->
                        if (indicator.isCanceled) return@iterateChildrenRecursively false
                        if (!file.isDirectory && shouldIndex(file)) {
                            indicator.text = "Indexing ${file.name}"
                            indicator.fraction = (visited.incrementAndGet().toDouble() / total.coerceAtLeast(1))
                            indexFile(file)
                        }
                        true
                    }
                }
                lastDurationMs = System.currentTimeMillis() - t0
                onDone(stats())
            }
        })
    }

    private fun countFiles(root: VirtualFile): Int {
        var count = 0
        VfsUtilCore.iterateChildrenRecursively(root, null) { f ->
            if (!f.isDirectory && shouldIndex(f)) count++
            true
        }
        return count
    }

    private fun shouldIndex(file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        val ext = file.extension?.lowercase() ?: return false
        if (ext in BINARY_EXTS) return false
        if (file.length > MAX_FILE_BYTES) return false
        // 跳过在忽略目录中的文件（通过路径片段判断）
        val path = file.path
        if (SKIP_DIRS.any { seg -> path.contains("/$seg/") || path.contains("\\$seg\\") }) return false
        return true
    }

    private fun indexFile(file: VirtualFile) {
        val path = file.path
        val ext = file.extension?.lowercase() ?: "txt"
        val content = try {
            String(file.contentsToByteArray(), Charsets.UTF_8)
        } catch (e: Exception) {
            return
        }
        meta[path] = FileMeta(path, ext, content.length)
        val seen = HashSet<String>()
        for (tok in TOKEN_REGEX.findAll(content)) {
            val term = tok.value.lowercase()
            if (term.length < 3) continue
            if (term in STOPWORDS) continue
            if (!seen.add(term)) continue
            inverted.computeIfAbsent(term) { ConcurrentHashMap.newKeySet() }.add(path)
        }
        indexedFileCount++
    }

    /**
     * 检索与查询相关的文件，按命中词数降序返回。
     */
    fun search(query: String, limit: Int = 15): List<CodeIndexHit> {
        lastQuery = query
        if (inverted.isEmpty()) return emptyList()
        val scores = HashMap<String, Int>()
        val langOf = HashMap<String, String>()
        for (tok in TOKEN_REGEX.findAll(query)) {
            val term = tok.value.lowercase()
            if (term.length < 3 || term in STOPWORDS) continue
            inverted[term]?.forEach { path ->
                scores[path] = (scores[path] ?: 0) + 1
                langOf.putIfAbsent(path, meta[path]?.language ?: "?")
            }
        }
        return scores.entries.sortedByDescending { it.value }
            .take(limit)
            .map { (path, score) -> CodeIndexHit(path, score, langOf[path] ?: "?") }
    }

    /** 获取若干命中文件的片段（供工具回传模型） */
    fun snippet(path: String, maxChars: Int = 1200): String {
        val vf = meta[path] ?: return "未索引文件：$path"
        return try {
            val root = ProjectRootManager.getInstance(project).contentRoots.firstOrNull()
            val file = root?.findFileByRelativePath(path.removePrefix(root.path).removePrefix("/")) ?: return vf.path
            String(file.contentsToByteArray(), Charsets.UTF_8).take(maxChars)
        } catch (e: Exception) {
            thisLogger().warn("snippet failed: $path", e)
            path
        }
    }

    companion object {
        private const val MAX_FILE_BYTES = 512 * 1024
        private val TOKEN_REGEX = Regex("[A-Za-z0-9_]{2,}")
        private val STOPWORDS = setOf(
            "the", "and", "for", "this", "that", "with", "from", "into", "public", "private",
            "static", "final", "return", "null", "true", "false", "void", "var", "val", "fun",
            "class", "import", "package", "new", "get", "set", "not", "are", "was", "you", "has"
        )

        fun getInstance(project: Project): CodeIndexService =
            project.getService(CodeIndexService::class.java)
    }
}
