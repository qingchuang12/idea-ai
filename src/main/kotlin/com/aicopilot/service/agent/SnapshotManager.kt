package com.aicopilot.service.agent

import com.intellij.history.Label
import com.intellij.history.LocalHistory
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager

/**
 * 基于平台 LocalHistory 的执行快照与回滚。
 *
 * 在 Agent 开始成批修改文件前打一个标签（快照），当用户对结果不满意时可一键回滚到该标签。
 */
@Service(Service.Level.PROJECT)
class SnapshotManager(private val project: Project) {

    private var lastLabel: Label? = null
    private var lastLabelName: String? = null

    /** 创建一个命名快照，返回快照名（失败返回 null）。 */
    fun snapshot(name: String): String? {
        return try {
            val label = LocalHistory.getInstance().putUserLabel(project, name)
            lastLabel = label
            lastLabelName = name
            name
        } catch (e: Exception) {
            thisLogger().warn("createSnapshot failed", e)
            null
        }
    }

    fun hasSnapshot(): Boolean = lastLabel != null

    fun lastSnapshotName(): String? = lastLabelName

    /**
     * 回滚到最近一次快照。必须在 EDT 上调用（内部会触发 VFS 变更）。
     * @return 是否成功
     */
    fun rollback(): Boolean {
        val label = lastLabel ?: return false
        val root = ProjectRootManager.getInstance(project).contentRoots.firstOrNull() ?: return false
        return try {
            label.revert(project, root)
            true
        } catch (e: Exception) {
            thisLogger().warn("rollback failed", e)
            false
        }
    }

    companion object {
        fun getInstance(project: Project): SnapshotManager =
            project.getService(SnapshotManager::class.java)
    }
}
