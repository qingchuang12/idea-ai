package com.aicopilot.ui

import com.aicopilot.model.AgentEvent
import com.aicopilot.model.AgentMode
import com.aicopilot.model.ChatMessage
import com.aicopilot.model.ContextItem
import com.aicopilot.model.MessageRole
import com.aicopilot.model.ModelConfig
import com.aicopilot.service.ChatService
import com.aicopilot.service.ContextManager
import com.aicopilot.service.MarkdownRenderer
import com.aicopilot.service.agent.AgentLoop
import com.aicopilot.service.agent.AgentOrchestrator
import com.aicopilot.service.agent.ToolContext
import com.aicopilot.ecosystem.RulesLoader
import com.aicopilot.ecosystem.SkillMarket
import com.aicopilot.settings.AIApplicationSettings
import com.aicopilot.settings.ChatHistoryState
import com.aicopilot.settings.ModelRegistry
import com.aicopilot.service.understanding.MemoryStore
import com.intellij.icons.AllIcons
import com.intellij.ide.dnd.FileCopyPasteUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import java.awt.Toolkit
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDropEvent
import java.awt.datatransfer.DataFlavor
import java.awt.event.HierarchyEvent
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.*
import java.awt.BorderLayout
import java.awt.Component
import javax.swing.*
import javax.swing.DefaultListModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * AI Copilot Tool Window 工厂
 */
class AIToolWindowFactory : ToolWindowFactory {
    
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = AIChatPanel(project)
        toolWindow.contentManager.factory.createContent(panel, "AI Copilot", false).also {
            toolWindow.contentManager.addContent(it)
        }
    }
}

/**
 * AI 聊天主面板 - 包含三个标签页
 */
class AIChatPanel(private val project: Project) : JPanel(BorderLayout()) {
    
    private val chatService = ChatService()
    private val contextManager = ContextManager.getInstance(project)
    private val markdownRenderer = MarkdownRenderer.getInstance()
    private val registry = ModelRegistry.getInstance()
    private val historyState = ChatHistoryState.getInstance()
    private val settings = AIApplicationSettings.getInstance()

    private val tabbedPane = JTabbedPane()

    // 聊天页组件
    private val messageList = DefaultListModel<ChatMessage>()
    private val chatListView = JBList(messageList)
    private val inputArea = JBTextArea(5, 50)
    private val streamingLabel = JLabel("").apply { isVisible = false }
    private val modelCombo = JComboBox<ModelItem>()
    private val newChatButton = JButton("New")
    private val modeSelector = JComboBox(arrayOf("Ask", "Agent"))
    private val modeCombo = JComboBox(AgentMode.entries.toTypedArray())
    private val capsLabel = JLabel("")
    private var atPopupOpen = false

    /** 已引用上下文的可移除片段（chip） */
    private val chipPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply { isOpaque = false }

    /** 输入区动作按钮：+ 附加上下文（文件/文件夹/当前文件/选区/截图）、发送、停止 */
    private val attachButton = JButton(AllIcons.General.Add)
    private val sendButton = JButton("发送", AllIcons.Actions.Forward)
    private val stopButton = JButton("停止").apply { isVisible = false }
    private val contextBadge = JLabel("")
    private var currentJob: kotlinx.coroutines.Job? = null

    /** 模型下拉项包装 */
    private data class ModelItem(val config: ModelConfig) {
        override fun toString(): String = config.displayLabel()
    }
    
    // 上下文页组件
    private val contextListModel = DefaultListModel<ContextItem>()
    private val contextListView = JBList(contextListModel)
    private val clearContextButton = JButton("Clear All")
    
    init {
        // 刷新 models.json 声明式模型
        registry.refreshJsonModels(project)
        // 厂商模型自动加载（内置预设，可切换）
        runCatching { registry.seedBuiltinModels() }
        runCatching { SkillMarket.getInstance().registerProjectSkills(project.basePath ?: "") }
        initializeUI()
        reloadModelCombo()
        loadHistory()
        setupAutoLoadOpenFile()
    }
    
    private fun initializeUI() {
        // 创建 TabbedPanel（原生聊天 + 上下文；不再提供空白的 WebView 外壳）
        tabbedPane.addTab("Chat", createChatPanel())
        tabbedPane.addTab("Context", createContextPanel())
        
        add(tabbedPane, BorderLayout.CENTER)
    }

    /** 重新载入模型下拉列表 */
    private fun reloadModelCombo() {
        modelCombo.removeAllItems()
        val models = registry.getEnabledModels()
        val active = registry.getActiveModel()
        models.forEach { modelCombo.addItem(ModelItem(it)) }
        active?.let { a ->
            for (i in 0 until modelCombo.itemCount) {
                if (modelCombo.getItemAt(i).config.id == a.id) {
                    modelCombo.selectedIndex = i
                    break
                }
            }
        }
    }

    /** 从持久化会话加载历史消息 */
    private fun loadHistory() {
        val session = historyState.getCurrentSession() ?: return
        messageList.clear()
        session.messages.forEach { m ->
            val role = when (m.role.lowercase()) {
                "user" -> MessageRole.USER
                "assistant" -> MessageRole.ASSISTANT
                else -> MessageRole.SYSTEM
            }
            messageList.addElement(ChatMessage(role = role, content = m.content))
        }
        if (messageList.size > 0) chatListView.ensureIndexIsVisible(messageList.size - 1)
    }

    /** 新建会话 */
    fun startNewSession() {
        historyState.createNewSession()
        messageList.clear()
    }
    


    /**
     * 组装 Agent 系统提示的附加上下文：项目规则 + 跨会话记忆。
     */
    private fun buildAgentExtraContext(): String = buildString {
        runCatching {
            val rules = RulesLoader.getInstance(project).asSystemPrompt()
            if (rules.isNotBlank()) append("\n\n").append(rules)
        }
        runCatching {
            val mem = MemoryStore.getInstance().asSystemContext()
            if (mem.isNotBlank()) append("\n\n").append(mem)
        }
    }

    /** Ask / Agent 模式判定 */
    private fun isAgentMode(): Boolean = modeSelector.selectedItem == "Agent"

    /** 更新模式能力提示标签 */
    private fun updateCapsLabel() {
        capsLabel.text = if (isAgentMode()) {
            "能力：工具调用 · 规则 · 记忆 · 技能 · MCP"
        } else {
            "Ask 模式：调试 / 概念解释 / 方案探讨（点 \"+\" 引用文件、文件夹、当前文件或选中代码）"
        }
        capsLabel.font = capsLabel.font.deriveFont(java.awt.Font.ITALIC, 11f)
    }

    /**
     * Ask 模式系统人设：AI 编程专家 + 回复语言 + 项目规则/记忆。
     */
    private fun buildAskSystemPrompt(): String = buildString {
        append("你是一个集成在 IntelliJ IDEA 中的 AI 编程专家（Ask 模式）。")
        append("你擅长：调试代码、排查运行时错误、解释编程概念、探讨实现方案。")
        append("回答要准确、简洁、面向工程师；必要时用代码说明，并清晰解释。")
        when (settings.replyLanguage) {
            "中文" -> append("请始终使用中文回答。")
            "English" -> append("Please always answer in English.")
        }
        val extra = buildAgentExtraContext()
        if (extra.isNotBlank()) append(extra)
    }

    // ========== @ 引入上下文 ==========
    private fun setupAtMention() {
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = onInputChanged()
            override fun removeUpdate(e: DocumentEvent?) {}
            override fun changedUpdate(e: DocumentEvent?) {}
        })
    }

    private fun onInputChanged() {
        if (atPopupOpen) return
        val caret = inputArea.caretPosition
        if (caret <= 0) return
        val text = inputArea.text
        if (caret - 1 < text.length && text[caret - 1] == '@') {
            showAtMentionPopup()
        }
    }

    private fun showAtMentionPopup() {
        atPopupOpen = true
        val candidates = collectAtCandidates()
        val listModel = DefaultListModel<VirtualFile>()
        candidates.forEach { listModel.addElement(it) }
        val list = JBList(listModel)
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                l: JList<*>, value: Any?, index: Int, isSel: Boolean, hasFocus: Boolean
            ): Component {
                val vf = value as? VirtualFile
                val txt = vf?.let { if (it.isDirectory) "📁 ${it.path}" else it.path } ?: value.toString()
                return super.getListCellRendererComponent(l, txt, index, isSel, hasFocus)
            }
        }
        val search = JBTextField(24)
        fun filter(q: String) {
            listModel.clear()
            val lower = q.lowercase()
            candidates.filter { it.path.lowercase().contains(lower) }.forEach { listModel.addElement(it) }
        }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = filter(search.text)
            override fun removeUpdate(e: DocumentEvent?) = filter(search.text)
            override fun changedUpdate(e: DocumentEvent?) {}
        })
        val panel = JPanel(BorderLayout()).apply {
            add(search, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, search)
            .setRequestFocus(true)
            .setMinSize(java.awt.Dimension(320, 200))
            .createPopup()

        fun confirm() {
            val vf = list.selectedValue ?: return
            applyAtMention(vf)
            popup.cancel()
        }
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                if (e?.clickCount == 2) confirm()
            }
        })
        search.addActionListener { confirm() }
        popup.addListener(object : com.intellij.openapi.ui.popup.JBPopupListener {
            override fun beforeShown(d: com.intellij.openapi.ui.popup.LightweightWindowEvent) {}
            override fun onClosed(e: com.intellij.openapi.ui.popup.LightweightWindowEvent) {
                atPopupOpen = false
            }
        })
        popup.showUnderneathOf(inputArea)
    }

    private fun applyAtMention(vf: VirtualFile) {
        val token = "@${vf.name} "
        val doc = inputArea.document
        val caret = inputArea.caretPosition
        var at = caret - 1
        while (at >= 0 && doc.getText(at, 1) != "@") at--
        val from = if (at >= 0) at else caret
        doc.remove(from, caret - from)
        doc.insertString(from, token, null)
        inputArea.caretPosition = from + token.length
        val added = if (vf.isDirectory) {
            val collected = mutableListOf<VirtualFile>()
            VfsUtilCore.iterateChildrenRecursively(vf, { true }) { child ->
                if (!child.isDirectory && isTextFile(child) && collected.size < 100) collected.add(child)
                collected.size < 100
            }
            contextManager.addFiles(collected)
        } else {
            contextManager.addFile(vf)?.let { listOf(it) } ?: emptyList()
        }
        if (added.isNotEmpty()) {
            added.forEach { addContextItem(it) }
            refreshContextList()
            updateContextBadge()
        }
    }

    private fun collectAtCandidates(): List<VirtualFile> {
        val result = mutableListOf<VirtualFile>()
        FileEditorManager.getInstance(project).openFiles.forEach { if (isTextFile(it)) result.add(it) }
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory && isTextFile(vf) && result.size < 300) result.add(vf)
            result.size < 300
        }
        ProjectRootManager.getInstance(project).contentRoots.forEach { if (it.isDirectory) result.add(it) }
        return result
    }

    private fun isTextFile(vf: VirtualFile): Boolean {
        val ext = vf.extension?.lowercase() ?: return false
        return ext in setOf(
            "txt", "md", "java", "kt", "kts", "py", "js", "ts", "jsx", "tsx",
            "html", "css", "scss", "json", "xml", "yaml", "yml", "sql",
            "sh", "bash", "rb", "php", "go", "rs", "cpp", "c", "h",
            "swift", "scala", "groovy", "lua", "ps1"
        )
    }

    // ========== 自动加载当前打开文件 ==========
    private fun setupAutoLoadOpenFile() {
        if (!settings.autoLoadOpenFile) return
        FileEditorManager.getInstance(project).openFiles.forEach { autoAddOpenFile(it) }
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    autoAddOpenFile(event.newFile)
                }
            }
        )
    }

    private fun autoAddOpenFile(vf: VirtualFile?) {
        if (vf == null || vf.isDirectory || !isTextFile(vf)) return
        if (contextManager.getContextItems().any { it.filePath == vf.path }) return
        contextManager.addFile(vf)?.let {
            addContextItem(it)
            refreshContextList()
            updateContextBadge()
        }
    }

    /** * 创建聊天页面
     */
    private fun createChatPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)

            // 顶部工具栏：模型选择 + 模式切换（Ask/Agent）+ 新建会话
            val topBar = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyBottom(8)
                // FlowLayout 保持组件原始尺寸，避免下拉框被拉伸变形
                val left = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)).apply {
                    add(JLabel("Model:"))
                    add(modelCombo)
                    add(JLabel("Mode:"))
                    add(modeSelector)
                    add(modeCombo)
                }
                add(left, BorderLayout.CENTER)
                add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0)).apply {
                    add(newChatButton)
                }, BorderLayout.EAST)
                modeSelector.toolTipText = "Ask：AI 专家问答（调试/概念/方案）；Agent：自主完成多步任务"
                modeCombo.toolTipText = "Light：即时执行；Heavy：先规划出 To-dos 并在任务看板中确认后分步执行"
                modeCombo.isEnabled = isAgentMode()
                updateCapsLabel()
                modeSelector.addActionListener {
                    modeCombo.isEnabled = isAgentMode()
                    updateCapsLabel()
                }
            }
            add(topBar, BorderLayout.NORTH)

            modelCombo.addActionListener {
                (modelCombo.selectedItem as? ModelItem)?.let {
                    registry.setActiveModel(it.config.id)
                }
            }
            newChatButton.addActionListener { startNewSession() }
            
            // 消息列表区域
            val chatScrollPanel = JBScrollPane(chatListView).apply {
                verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }
            
            chatListView.cellRenderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>,
                    value: Any,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): Component {
                    if (value is ChatMessage) {
                        val html = renderMessage(value)
                        return super.getListCellRendererComponent(list, html, index, isSelected, cellHasFocus)
                            .apply {
                                iconTextGap = 10
                                border = JBUI.Borders.empty(5)
                            }
                    }
                    return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                }
            }
            
            // 输入区域
            val inputPanel = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(10)

                // 顶部：Ask/Agent 能力提示
                add(capsLabel, BorderLayout.NORTH)

                // 中部：上下文片段 + 附件工具条 + 文本输入
                val middle = JPanel(BorderLayout()).apply {
                    // 已引用上下文的可移除片段（chip）
                    add(chipPanel, BorderLayout.NORTH)

                    val inner = JPanel(BorderLayout()).apply {
                        // 附加工具条：左侧 + 按钮与上下文徽标
                        val attachBar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
                            isOpaque = false
                            attachButton.toolTipText = "添加上下文：文件 / 文件夹 / 当前打开文件 / 选中代码 / 粘贴截图"
                            add(attachButton)
                            contextBadge.font = font.deriveFont(java.awt.Font.ITALIC, 11f)
                            contextBadge.foreground = java.awt.Color.GRAY
                            add(contextBadge)
                        }
                        add(attachBar, BorderLayout.NORTH)

                        inputArea.lineWrap = true
                        inputArea.wrapStyleWord = true
                        add(JBScrollPane(inputArea), BorderLayout.CENTER)
                    }
                    add(inner, BorderLayout.CENTER)
                }
                add(middle, BorderLayout.CENTER)

                // 底部动作条：左侧状态，右侧 [停止]/[发送] 统一动作簇
                val actionBar = JPanel(BorderLayout()).apply {
                    border = JBUI.Borders.emptyTop(6)
                    add(streamingLabel, BorderLayout.CENTER)
                    add(JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0)).apply {
                        add(stopButton)
                        add(sendButton)
                    }, BorderLayout.EAST)
                }
                sendButton.toolTipText = "发送（Enter）"
                add(actionBar, BorderLayout.SOUTH)

                attachButton.addActionListener { showAttachMenu() }
                // 从系统文件管理器 / 项目树拖拽文件或文件夹到聊天即可加入上下文
                this.dropTarget = DropTarget(this, DnDConstants.ACTION_COPY_OR_MOVE, object : DropTargetAdapter() {
                    override fun drop(dtde: DropTargetDropEvent) {
                        val vFiles = try {
                            dtde.acceptDrop(DnDConstants.ACTION_COPY_OR_MOVE)
                            FileCopyPasteUtil.getFileList(dtde.transferable)
                                ?.mapNotNull { LocalFileSystem.getInstance().refreshAndFindFileByIoFile(it) }
                        } catch (e: Exception) {
                            null
                        }
                        if (vFiles.isNullOrEmpty()) {
                            dtde.dropComplete(false)
                        } else {
                            addDroppedFiles(vFiles)
                            dtde.dropComplete(true)
                        }
                    }
                })
                rebuildChips()

                // 发送按钮作为主按钮（新 UI 蓝色高亮），对齐 qoder 的图标化主操作
                addHierarchyListener { e ->
                    if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) {
                        rootPane?.defaultButton = sendButton
                    }
                }
            }
            
            add(chatScrollPanel, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
            
            // 绑定事件
            setupChatListeners()
        }
    }
    


    /** * 创建上下文页面
     */
    private fun createContextPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            
            val headerLabel = JLabel("Context Items").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }
            
            val contextScrollPane = JBScrollPane(contextListView)
            
            val buttonPanel = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 4)).apply {
                add(clearContextButton)
            }
            
            add(headerLabel, BorderLayout.NORTH)
            add(contextScrollPane, BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.SOUTH)
            
            // 绑定事件
            clearContextButton.addActionListener {
                contextManager.clearAllContext()
                refreshContextList()
            }
            
            // 右键菜单移除项
            contextListView.addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mousePressed(e: java.awt.event.MouseEvent?) {
                    if (e?.isPopupTrigger == true) {
                        val index = contextListView.locationToIndex(e.point)
                        if (index >= 0) {
                            val item = contextListModel.getElementAt(index)
                            showContextMenu(item, e.x, e.y)
                        }
                    }
                }
                
                override fun mouseReleased(e: java.awt.event.MouseEvent?) {
                    if (e?.isPopupTrigger == true) {
                        val index = contextListView.locationToIndex(e.point)
                        if (index >= 0) {
                            val item = contextListModel.getElementAt(index)
                            showContextMenu(item, e.x, e.y)
                        }
                    }
                }
            })
        }
    }
    


    /** * 显示上下文项的右键菜单
     */
    private fun showContextMenu(item: ContextItem, x: Int, y: Int) {
        val menu = JPopupMenu()
        val removeAction = JMenuItem("Remove")
        removeAction.addActionListener {
            contextManager.removeContextItem(item.id)
            refreshContextList()
        }
        menu.add(removeAction)
        menu.show(contextListView, x, y)
    }
    


    // ========== 输入区：附加上下文（解决聊天无法引用文件/文件夹/当前页面） ==========
    private fun showAttachMenu() {
        val menu = JPopupMenu()
        fun item(label: String, tip: String, action: () -> Unit): JMenuItem {
            val mi = JMenuItem(label)
            mi.toolTipText = tip
            mi.addActionListener { action() }
            return mi
        }
        menu.add(item("📄 添加文件…", "选择一个或多个文件加入上下文") { addFileContext() })
        menu.add(item("📁 添加文件夹…", "递归加入文件夹下全部文本文件") { addFolderContext() })
        menu.add(item("📋 当前打开文件", "把当前编辑器打开的文件加入上下文") { addCurrentFile() })
        menu.add(item("📝 当前选中代码", "把编辑器中选中的代码片段加入上下文") { addSelectionContext() })
        menu.add(item("📷 粘贴剪贴板图片", "把剪贴板中的截图作为多模态上下文加入") { addImageFromClipboard() })
        val size = attachButton.size
        menu.show(attachButton, 0, size.height)
    }

    private fun addFileContext() {
        val descriptor = FileChooserDescriptor(true, false, false, false, true, true)
        FileChooser.chooseFiles(descriptor, project, null) { files ->
            val added = contextManager.addFiles(files)
            if (added.isNotEmpty()) {
                added.forEach { addContextItem(it) }
                refreshContextList()
                updateContextBadge()
            }
        }
    }

    private fun addFolderContext() {
        val descriptor = FileChooserDescriptor(false, true, false, false, false, false)
        val folder = FileChooser.chooseFile(descriptor, project, null) ?: return
        val collected = mutableListOf<VirtualFile>()
        VfsUtilCore.iterateChildrenRecursively(folder, { true }) { child ->
            if (!child.isDirectory && isTextFile(child) && collected.size < 200) collected.add(child)
            collected.size < 200
        }
        val added = contextManager.addFiles(collected)
        if (added.isNotEmpty()) {
            added.forEach { addContextItem(it) }
            refreshContextList()
            updateContextBadge()
        } else {
            JOptionPane.showMessageDialog(this, "该文件夹下没有可加入的文本文件。", "Empty", JOptionPane.INFORMATION_MESSAGE)
        }
    }

    private fun addCurrentFile() {
        val vf = FileEditorManager.getInstance(project).selectedEditor?.file
            ?: FileEditorManager.getInstance(project).openFiles.firstOrNull() ?: run {
            JOptionPane.showMessageDialog(this, "当前没有打开的文件。", "No File", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        contextManager.addFile(vf)?.let {
            addContextItem(it)
            refreshContextList()
            updateContextBadge()
        }
    }

    private fun addSelectionContext() {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: run {
            JOptionPane.showMessageDialog(this, "请先打开一个文件。", "No Editor", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val text = editor.selectionModel.selectedText ?: run {
            JOptionPane.showMessageDialog(this, "请先在编辑器中选中一段代码。", "No Selection", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        val name = editor.virtualFile?.let { "Selection: ${it.name}" } ?: "Selected Text"
        val item = contextManager.addSelection(text, name)
        addContextItem(item)
        refreshContextList()
        updateContextBadge()
    }

    private fun updateContextBadge() {
        val n = contextManager.getContextItems().size
        contextBadge.text = if (n > 0) "已引用 $n 个上下文" else ""
        rebuildChips()
    }

    /** 把已引用上下文渲染为可移除的 chip 片段（对齐 qoder 的附件胶囊） */
    private fun rebuildChips() {
        chipPanel.removeAll()
        contextManager.getContextItems().forEach { item ->
            val name = item.filePath ?: item.displayName
            val chip = JButton("📎 $name  ✕").apply {
                font = font.deriveFont(11f)
                toolTipText = item.filePath ?: name
                addActionListener {
                    contextManager.removeContextItem(item.id)
                    refreshContextList()
                    updateContextBadge()
                }
            }
            chipPanel.add(chip)
        }
        chipPanel.revalidate()
        chipPanel.repaint()
    }

    /** 拖拽进来的文件/文件夹直接加入上下文 */
    private fun addDroppedFiles(files: List<VirtualFile>) {
        val added = contextManager.addFiles(files)
        if (added.isNotEmpty()) {
            added.forEach { addContextItem(it) }
            refreshContextList()
            updateContextBadge()
        }
    }

    /** 剪贴板图片 → 多模态上下文（截图即贴） */
    private fun addImageFromClipboard() {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        val contents = clipboard.getContents(null)
        if (contents == null || !contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
            JOptionPane.showMessageDialog(this, "剪贴板中没有图片。", "No Image", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        try {
            val img = contents.getTransferData(DataFlavor.imageFlavor) as? java.awt.image.BufferedImage ?: return
            val tmp = File.createTempFile("aipaste-", ".png").apply { deleteOnExit() }
            ImageIO.write(img, "png", tmp)
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(tmp)
            vf?.let {
                contextManager.addFile(it)?.let { item ->
                    addContextItem(item)
                    refreshContextList()
                    updateContextBadge()
                }
            }
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "无法读取剪贴板图片：${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    /** 运行态切换：发送中禁用输入动作、显示停止按钮 */
    private fun setRunning(running: Boolean) {
        sendButton.isEnabled = !running
        attachButton.isEnabled = !running
        stopButton.isVisible = running
        streamingLabel.isVisible = running
    }

    /** * 设置聊天相关的事件监听器
     */
    private fun setupChatListeners() {
        sendButton.addActionListener {
            sendMessage()
        }
        stopButton.addActionListener {
            currentJob?.cancel()
            setRunning(false)
            streamingLabel.text = "已停止"
        }
        setupAtMention()
        
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = autoGrowInput()
            override fun removeUpdate(e: DocumentEvent?) = autoGrowInput()
            override fun changedUpdate(e: DocumentEvent?) {}
        })
        
        // Enter 发送；Shift+Enter 换行；Ctrl+Enter 同样发送（对齐 qoder 习惯）
        val enter = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0)
        val ctrlEnter = javax.swing.KeyStroke.getKeyStroke(
            java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK
        )
        inputArea.inputMap.put(enter, "send")
        inputArea.inputMap.put(ctrlEnter, "send")
        inputArea.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                sendMessage()
            }
        })
        autoGrowInput()
    }

    /** 输入框随内容自动增高（上限 12 行） */
    private fun autoGrowInput() {
        val lines = (inputArea.text.count { it == '\n' } + 1).coerceAtLeast(2)
        inputArea.rows = lines.coerceAtMost(12)
        inputArea.revalidate()
    }
    


    /** * 发送消息
     */
    private fun sendMessage() {
        val text = inputArea.text.trim()
        if (text.isEmpty()) return
        
        // 添加用户消息
        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = text,
            contextItems = contextManager.getContextItems()
        )
        messageList.addElement(userMessage)

        // 持久化用户消息
        val contextPaths = contextManager.getContextItems().mapNotNull { it.filePath }
        historyState.appendMessage("user", text, contextPaths)
        
        // 清空输入框
        inputArea.text = ""
        
        // 滚动到底部
        chatListView.ensureIndexIsVisible(messageList.size - 1)

        // Agent 模式：走工具调用循环
        if (isAgentMode()) {
            if (modeCombo.selectedItem == AgentMode.HEAVY) {
                runHeavyAgent(text)
            } else {
                runAgent(text)
            }
            return
        }
        
        // 发送请求并处理流式响应（Ask 模式：注入 AI 专家系统提示）
        val askSystemPrompt = if (settings.enableSmartChat) buildAskSystemPrompt() else null
        setRunning(true)
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            streamingLabel.text = "Thinking..."
            
            messageList.addElement(ChatMessage(role = MessageRole.ASSISTANT, content = ""))
            historyState.appendMessage("assistant", "")
            
            val messageIndex = messageList.size - 1
            val accumulated = StringBuilder()
            
            try {
                chatService.sendChatStream(
                    messages = (0 until messageList.size - 1).map { messageList.getElementAt(it) },
                    contextItems = contextManager.getContextItems(),
                    systemPrompt = askSystemPrompt
                ).collect { chunk ->
                    accumulated.append(chunk)
                    // 更新 UI
                    SwingUtilities.invokeLater {
                        messageList.set(messageIndex, ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = accumulated.toString()
                        ))
                        chatListView.ensureIndexIsVisible(messageIndex)
                    }
                }
                // 流结束，落盘完整助手回复
                historyState.updateLastMessage(accumulated.toString())
            } catch (e: Exception) {
                historyState.updateLastMessage(accumulated.toString())
                SwingUtilities.invokeLater {
                    JOptionPane.showMessageDialog(
                        this@AIChatPanel,
                        "Error: ${e.message}",
                        "AI Chat Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            } finally {
                streamingLabel.text = ""
                setRunning(false)
            }
        }
    }
    


    /** * Agent 模式执行：通过 AgentLoop 驱动工具调用循环，并将事件展示到消息流。
     */
    private fun runAgent(task: String) {
        val model = registry.getActiveModel()
        if (model == null) {
            JOptionPane.showMessageDialog(this, "请先在设置中配置并启用一个模型。", "No Model", JOptionPane.WARNING_MESSAGE)
            return
        }

        val ctx = ToolContext(project) { title, detail ->
            var allow = false
            SwingUtilities.invokeAndWait {
                allow = Messages.showYesNoDialog(
                    project, detail, title, "Allow", "Deny", Messages.getQuestionIcon()
                ) == Messages.YES
            }
            allow
        }

        val systemPreamble = "你是一个自主编码智能体（Agent），运行在 IntelliJ IDEA 中。" +
            "你可以调用工具读写文件、执行终端命令、搜索工程与检索符号，端到端完成用户任务。" +
            "在修改文件或执行命令前请说明你的意图。完成后给出简洁的总结。" +
            buildAgentExtraContext()

        setRunning(true)
        currentJob = CoroutineScope(Dispatchers.Main).launch {
            streamingLabel.text = "Agent working..."
            try {
                withContext(Dispatchers.IO) {
                    AgentLoop().run(
                        userTask = task,
                        systemPreamble = systemPreamble,
                        contextItems = contextManager.getContextItems(),
                        model = model,
                        ctx = ctx
                    ) { event ->
                        SwingUtilities.invokeLater { appendAgentEvent(event) }
                    }
                }
            } catch (e: Exception) {
                SwingUtilities.invokeLater { appendAgentEvent(AgentEvent.Error(e.message ?: "agent failed")) }
            } finally {
                streamingLabel.text = ""
                setRunning(false)
            }
        }
    }





    /** * Agent 重量级模式：先生成计划并打开任务看板，用户在看板中确认后分步执行。
     */
    private fun runHeavyAgent(task: String) {
        val model = registry.getActiveModel()
        if (model == null) {
            JOptionPane.showMessageDialog(this, "请先在设置中配置并启用一个模型。", "No Model", JOptionPane.WARNING_MESSAGE)
            return
        }
        messageList.addElement(
            ChatMessage(MessageRole.SYSTEM, "🗂️ 已进入规划模式，正在生成 To-dos 计划，请在「AI Agent Board」视窗中查看与确认。")
        )
        chatListView.ensureIndexIsVisible(messageList.size - 1)

        AgentOrchestrator.getInstance(project).startPlanning(task, model)
        AgentBoardToolWindowFactory.activate(project)
    }

    /** 将 Agent 事件追加到消息流 */
    private fun appendAgentEvent(event: AgentEvent) {
        val msg: ChatMessage? = when (event) {
            is AgentEvent.Thinking -> ChatMessage(MessageRole.SYSTEM, "⏳ ${event.text}")
            is AgentEvent.AssistantMessage -> ChatMessage(MessageRole.ASSISTANT, event.text)
            is AgentEvent.ToolInvoked -> ChatMessage(
                MessageRole.SYSTEM, "🔧 调用工具 `${event.call.name}`\n参数: ${event.call.argumentsJson}"
            )
            is AgentEvent.ToolFinished -> {
                val icon = if (event.ok) "✅" else "❌"
                val out = event.output.take(1500)
                ChatMessage(MessageRole.SYSTEM, "$icon `${event.call.name}` 结果:\n```\n$out\n```")
            }
            is AgentEvent.Error -> ChatMessage(MessageRole.SYSTEM, "❌ 错误: ${event.message}")
            is AgentEvent.Finished -> {
                historyState.appendMessage("assistant", event.finalText)
                ChatMessage(MessageRole.ASSISTANT, event.finalText)
            }
        }
        if (msg != null) {
            messageList.addElement(msg)
            chatListView.ensureIndexIsVisible(messageList.size - 1)
        }
    }



    /** * 渲染单条消息为 HTML
     */
    private fun renderMessage(message: ChatMessage): String {
        val roleIcon = when (message.role) {
            MessageRole.USER -> "👤"
            MessageRole.ASSISTANT -> "🤖"
            MessageRole.SYSTEM -> "⚙️"
        }
        
        val htmlContent = markdownRenderer.renderToHtml(message.content)
        
        return "<html><body>$roleIcon <b>${message.role.name}</b>: $htmlContent</body></html>"
    }
    


    /** * 刷新上下文列表
     */
    fun refreshContextList() {
        contextListModel.clear()
        contextManager.getContextItems().forEach {
            contextListModel.addElement(it)
        }
    }
    


    /** * 添加上下文项并刷新
     */
    fun addContextItem(item: ContextItem) {
        contextListModel.addElement(item)
    }



    /** * 将 AI 生成的结果以助手消息形式展示在聊天流中（供各类生成类 Action 复用）。
     */
    fun showGeneratedResult(title: String, text: String) {
        val content = "**$title**\n\n$text"
        messageList.addElement(ChatMessage(role = MessageRole.ASSISTANT, content = content))
        chatListView.ensureIndexIsVisible(messageList.size - 1)
        historyState.appendMessage("assistant", content)
    }

    companion object {
        /** 在项目中定位当前 AI Copilot 聊天面板实例。 */
        fun find(project: Project): AIChatPanel? {
            val tw = ToolWindowManager.getInstance(project).getToolWindow("AI Copilot") ?: return null
            return tw.contentManager.contents.mapNotNull { it.component as? AIChatPanel }.firstOrNull()
        }
    }
}
