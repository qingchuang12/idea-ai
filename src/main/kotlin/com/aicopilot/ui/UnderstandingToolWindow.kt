package com.aicopilot.ui

import com.aicopilot.ecosystem.McpClient
import com.aicopilot.ecosystem.RulesLoader
import com.aicopilot.ecosystem.SkillMarket
import com.aicopilot.service.understanding.CodeIndexService
import com.aicopilot.service.understanding.KnowledgeBase
import com.aicopilot.service.understanding.MemoryStore
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.*

/**
 * 「AI Knowledge」工具视窗：把 Phase 5（深度工程理解）与 Phase 6（开放生态）的能力集中呈现，
 * 提供代码索引、跨会话记忆、知识库、项目规则、技能库（手动添加）、MCP 连接（手动添加）的可视化管理入口。
 *
 * 布局规范：顶部为工具栏（FlowLayout，按钮保持原始尺寸），主内容区占 CENTER 自适应伸缩，
 * 表单统一使用 FormBuilder 对齐，避免组件被拉伸变形。
 */
class AIKnowledgeToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AIKnowledgePanel(project)
        toolWindow.contentManager.factory.createContent(panel, "AI Knowledge", false)
            .also { toolWindow.contentManager.addContent(it) }
    }
}

class AIKnowledgePanel(private val project: Project) : JPanel(BorderLayout()) {

    private val indexSvc = CodeIndexService.getInstance(project)
    private val kb = KnowledgeBase.getInstance()
    private val mem = MemoryStore.getInstance()
    private val rules = RulesLoader.getInstance(project)
    private val skills = SkillMarket.getInstance()
    private val mcp = McpClient.getInstance()

    private val tabbed = JTabbedPane()

    init {
        tabbed.addTab("Code Index", buildIndexTab())
        tabbed.addTab("Memory", buildMemoryTab())
        tabbed.addTab("Knowledge", buildKnowledgeTab())
        tabbed.addTab("Rules", buildRulesTab())
        tabbed.addTab("Skills", buildSkillsTab())
        tabbed.addTab("MCP", buildMcpTab())
        add(tabbed, BorderLayout.CENTER)
    }

    /** 左对齐工具栏：按钮保持自身尺寸，不被拉伸 */
    private fun toolbar(vararg comps: JComponent): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            comps.forEach { add(it) }
        }

    /** 搜索行：输入框自适应宽度 + 右侧按钮 */
    private fun searchRow(field: JTextField, vararg buttons: JComponent): JPanel =
        JPanel(BorderLayout(6, 0)).apply {
            add(field, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                buttons.forEach { add(it) }
            }, BorderLayout.EAST)
        }

    // ========== Code Index ==========
    private fun buildIndexTab(): Component {
        val stats = JBLabel(indexSvc.stats())
        val searchField = JTextField()
        val result = JBTextArea().apply { isEditable = false }
        val reindex = JButton("Re-index Codebase").apply {
            addActionListener {
                isEnabled = false
                indexSvc.indexAsync { s ->
                    SwingUtilities.invokeLater {
                        stats.text = s
                        isEnabled = true
                    }
                }
            }
        }
        val searchBtn = JButton("Search").apply {
            addActionListener {
                val hits = indexSvc.search(searchField.text, 20)
                result.text = if (hits.isEmpty()) "无命中。" else hits.joinToString("\n") {
                    "${it.score}\t${it.language}\t${it.filePath}"
                }
            }
        }
        searchField.addActionListener { searchBtn.doClick() }

        val north = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(toolbar(reindex, stats).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(8))
            add(searchRow(searchField, searchBtn).apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(8))
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(north, BorderLayout.NORTH)
            add(JBScrollPane(result), BorderLayout.CENTER)
        }
    }

    // ========== Memory ==========
    private fun buildMemoryTab(): Component {
        val listArea = JBTextArea().apply { isEditable = false }
        val keyF = JTextField()
        val contentA = JBTextArea(3, 30)
        val kindCombo = JComboBox(arrayOf("FACT", "PREFERENCE", "PROJECT", "FEEDBACK"))
        val tagsF = JTextField()
        val recallF = JTextField()
        val recallR = JBTextArea(6, 30).apply { isEditable = false }

        fun refresh() {
            listArea.text = mem.all().joinToString("\n") { "[${it.kind}] ${it.key}: ${it.content} (${it.tags})" }
        }
        refresh()

        val saveBtn = JButton("Save Memory").apply {
            addActionListener {
                val key = keyF.text.trim()
                val content = contentA.text.trim()
                if (key.isBlank() || content.isBlank()) {
                    Messages.showWarningDialog("key 与 content 不能为空", "Memory")
                    return@addActionListener
                }
                mem.remember(key, content, kindCombo.selectedItem as String, tagsF.text)
                keyF.text = ""; contentA.text = ""; tagsF.text = ""
                refresh()
            }
        }
        val recallBtn = JButton("Recall").apply {
            addActionListener {
                recallR.text = mem.recall(recallF.text, 8).joinToString("\n") { "[${it.kind}] ${it.key}: ${it.content}" }
            }
        }
        val refreshBtn = JButton("Refresh").apply { addActionListener { refresh() } }
        recallF.addActionListener { recallBtn.doClick() }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent("Key:", keyF)
            .addLabeledComponent("Kind:", kindCombo)
            .addLabeledComponent("Tags:", tagsF)
            .addLabeledComponent("Content:", JBScrollPane(contentA))
            .addComponent(toolbar(saveBtn, refreshBtn))
            .panel

        val south = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(searchRow(recallF, recallBtn), BorderLayout.NORTH)
            add(JBScrollPane(recallR).apply { preferredSize = Dimension(0, 120) }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(form, BorderLayout.NORTH)
            add(JBScrollPane(listArea), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }
    }

    // ========== Knowledge ==========
    private fun buildKnowledgeTab(): Component {
        val listArea = JBTextArea().apply { isEditable = false }
        val queryF = JTextField()
        val queryR = JBTextArea(6, 30).apply { isEditable = false }

        fun refresh() {
            listArea.text = kb.all().joinToString("\n") { "${it.type}\t${it.title} (${it.content.length} chars)" }
        }
        refresh()

        val addFileBtn = JButton("Add File…").apply {
            addActionListener {
                val fc = JFileChooser()
                if (fc.showOpenDialog(this@AIKnowledgePanel) == JFileChooser.APPROVE_OPTION) {
                    val f = fc.selectedFile
                    val text = runCatching { f.readText(Charsets.UTF_8) }.getOrDefault("")
                    kb.addFile(f.absolutePath, text)
                    refresh()
                }
            }
        }
        val addTextBtn = JButton("Add Text…").apply {
            addActionListener {
                val title = JOptionPane.showInputDialog(this, "知识标题") as? String
                val content = JOptionPane.showInputDialog(this, "知识内容") as? String
                if (!title.isNullOrBlank() && !content.isNullOrBlank()) {
                    kb.addText(title, content); refresh()
                }
            }
        }
        val queryBtn = JButton("Query").apply {
            addActionListener {
                queryR.text = kb.query(queryF.text, 5).joinToString("\n\n") { (d, s) -> "[${d.title}]\n$s" }
            }
        }
        val refreshBtn = JButton("Refresh").apply { addActionListener { refresh() } }
        queryF.addActionListener { queryBtn.doClick() }

        val south = JPanel(BorderLayout(0, 6)).apply {
            border = JBUI.Borders.emptyTop(8)
            add(searchRow(queryF, queryBtn), BorderLayout.NORTH)
            add(JBScrollPane(queryR).apply { preferredSize = Dimension(0, 140) }, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(toolbar(addFileBtn, addTextBtn, refreshBtn), BorderLayout.NORTH)
            add(JBScrollPane(listArea), BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }
    }

    // ========== Rules ==========
    private fun buildRulesTab(): Component {
        val listModel = DefaultListModel<RulesLoader.Rule>()
        val list = JBList(listModel).apply {
            cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    l: JList<*>, value: Any?, index: Int, isSel: Boolean, hasFocus: Boolean
                ): Component {
                    val name = (value as? RulesLoader.Rule)?.name ?: value.toString()
                    return super.getListCellRendererComponent(l, name, index, isSel, hasFocus)
                }
            }
        }
        val area = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        fun refresh() {
            listModel.clear()
            rules.listRules().forEach { listModel.addElement(it) }
            area.text = if (listModel.size() == 0)
                "（未加载任何规则。在工程根创建 .rules 目录或 *.rules 文件，或点击 Add Rule 新建。）"
            else "（左侧选择规则查看内容）"
        }
        list.addListSelectionListener {
            val r = list.selectedValue ?: return@addListSelectionListener
            area.text = "📄 ${r.name}\n\n${r.content}"
        }
        refresh()
        val reload = JButton("Reload").apply { addActionListener { rules.refresh(); refresh() } }
        val addBtn = JButton("Add Rule…").apply { addActionListener { showAddRuleDialog { refresh() } } }
        val delBtn = JButton("Delete").apply {
            addActionListener {
                val r = list.selectedValue ?: return@addActionListener
                if (rules.deleteRule(r)) refresh()
            }
        }
        val split = JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            JBScrollPane(list),
            JBScrollPane(area)
        ).apply {
            dividerLocation = 200
            border = null
        }
        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(toolbar(addBtn, reload, delBtn), BorderLayout.NORTH)
            add(split, BorderLayout.CENTER)
        }
    }

    private fun showAddRuleDialog(onDone: () -> Unit) {
        val nameF = JTextField(24)
        val contentA = JBTextArea(8, 40)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Rule name:", nameF)
            .addLabeledComponent("Content:", JBScrollPane(contentA))
            .panel
        val res = JOptionPane.showConfirmDialog(this, panel, "Add Project Rule", JOptionPane.OK_CANCEL_OPTION)
        if (res != JOptionPane.OK_OPTION) return
        val name = nameF.text.trim()
        val content = contentA.text.trim()
        if (name.isBlank() || content.isBlank()) {
            Messages.showWarningDialog("name 与 content 不能为空", "Rule")
            return
        }
        if (rules.addRule(name, content)) onDone() else Messages.showErrorDialog("创建规则失败（无法写入工程目录）", "Rule")
    }

    // ========== Skills ==========
    private fun buildSkillsTab(): Component {
        val area = JBTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
        fun refresh() {
            area.text = skills.getAllSkills().joinToString("\n\n") { "🧩 ${it.name}\n${it.description}" }
        }
        refresh()
        val refreshBtn = JButton("Refresh").apply {
            addActionListener { skills.refresh(); skills.registerProjectSkills(project.basePath ?: ""); refresh() }
        }
        val addBtn = JButton("Add Skill…").apply {
            addActionListener { showAddSkillDialog { refresh() } }
        }
        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(toolbar(addBtn, refreshBtn), BorderLayout.NORTH)
            add(JBScrollPane(area), BorderLayout.CENTER)
        }
    }

    private fun showAddSkillDialog(onDone: () -> Unit) {
        val nameF = JTextField(24)
        val descF = JTextField(32)
        val instrA = JBTextArea(8, 40)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Skill name (id):", nameF)
            .addLabeledComponent("Description:", descF)
            .addLabeledComponent("Instructions:", JBScrollPane(instrA))
            .panel
        val res = JOptionPane.showConfirmDialog(this, panel, "Add Skill (manual)", JOptionPane.OK_CANCEL_OPTION)
        if (res != JOptionPane.OK_OPTION) return
        val name = nameF.text.trim()
        val desc = descF.text.trim()
        val instr = instrA.text.trim()
        if (name.isBlank() || desc.isBlank() || instr.isBlank()) {
            Messages.showWarningDialog("name / description / instructions 不能为空", "Skill")
            return
        }
        if (skills.addSkill(name, desc, instr)) onDone()
        else Messages.showErrorDialog("创建技能失败（无法写入 ~/.ai-skills）", "Skill")
    }

    // ========== MCP ==========
    private fun buildMcpTab(): Component {
        val listModel = DefaultListModel<String>()
        val list = JBList(listModel)
        val statusArea = JBTextArea(4, 30).apply { isEditable = false }

        fun refresh() {
            listModel.clear()
            mcp.getConfigs().forEach { cfg ->
                val connected = if (mcp.isConnected(cfg.name)) "●connected" else "○disconnected"
                listModel.addElement("${cfg.name}  [$connected]  ${cfg.transport}")
            }
            if (listModel.size() == 0) listModel.addElement("（暂无 MCP 服务器，点击 Add Server 添加）")
        }
        refresh()

        val addBtn = JButton("Add Server…").apply {
            addActionListener { showAddMcpDialog { refresh() } }
        }
        val connectAll = JButton("Connect All").apply {
            addActionListener {
                mcp.getConfigs().filter { it.enabled }.forEach { cfg ->
                    mcp.connectAsync(cfg) { res ->
                        SwingUtilities.invokeLater {
                            statusArea.text = "connect ${cfg.name}: ${if (res.isSuccess) "ok" else res.exceptionOrNull()?.message}"
                            refresh()
                        }
                    }
                }
            }
        }
        val removeBtn = JButton("Remove").apply {
            addActionListener {
                val sel = list.selectedValue ?: return@addActionListener
                val name = sel.substringBefore("  ")
                mcp.removeConfig(name); refresh()
            }
        }

        return JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(10)
            add(toolbar(addBtn, connectAll, removeBtn), BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(JBScrollPane(statusArea).apply { preferredSize = Dimension(0, 80) }, BorderLayout.SOUTH)
        }
    }

    private fun showAddMcpDialog(onDone: () -> Unit) {
        val nameF = JTextField(24)
        val transportCombo = JComboBox(arrayOf("STDIO", "SSE"))
        val cmdF = JTextField(30)
        val argsF = JTextField(30)
        val envF = JTextField(30)
        val urlF = JTextField(30)
        val connectNow = JCheckBox("立即连接", true)
        val panel = FormBuilder.createFormBuilder()
            .addLabeledComponent("Name:", nameF)
            .addLabeledComponent("Transport:", transportCombo)
            .addLabeledComponent("Command (stdio):", cmdF)
            .addLabeledComponent("Args (space separated):", argsF)
            .addLabeledComponent("Env (KEY=V;KEY=V):", envF)
            .addLabeledComponent("URL (sse):", urlF)
            .addComponent(connectNow)
            .panel
        val result = JOptionPane.showConfirmDialog(this, panel, "Add MCP Server", JOptionPane.OK_CANCEL_OPTION)
        if (result != JOptionPane.OK_OPTION) return
        val cfg = McpClient.McpServerConfig(
            name = nameF.text.trim(),
            transport = (transportCombo.selectedItem as String),
            command = cmdF.text.trim(),
            args = argsF.text.trim(),
            env = envF.text.trim(),
            url = urlF.text.trim(),
            enabled = true
        )
        if (cfg.name.isBlank()) { Messages.showWarningDialog("name 不能为空", "MCP"); return }
        mcp.saveConfig(cfg)
        if (connectNow.isSelected) {
            mcp.connectAsync(cfg) { onDone() }
        } else {
            onDone()
        }
    }

    companion object {
        fun activate(project: Project) {
            com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
                .getToolWindow("AI Knowledge")?.show()
        }
    }
}
