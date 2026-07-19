package com.aicopilot.ui

import com.aicopilot.model.ChatMessage
import com.aicopilot.model.ContextItem
import com.aicopilot.model.MessageRole
import com.aicopilot.service.ChatService
import com.aicopilot.service.ContextManager
import com.aicopilot.service.MarkdownRenderer
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
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
        toolWindow.contentManager.factory.createContent(panel, "", false).also {
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
    
    private val tabbedPane = JTabbedPane()
    
    // 聊天页组件
    private val messageList = DefaultListModel<ChatMessage>()
    private val chatListView = JBList(messageList)
    private val inputArea = JBTextArea(5, 50)
    private val sendButton = JButton("Send")
    private val streamingLabel = JLabel("")
    
    // 上下文页组件
    private val contextListModel = DefaultListModel<ContextItem>()
    private val contextListView = JBList(contextListModel)
    private val clearContextButton = JButton("Clear All")
    
    // WebView 页组件（懒加载）
    private var webViewPanel: Component? = null
    
    init {
        initializeUI()
    }
    
    private fun initializeUI() {
        // 创建 TabbedPanel
        tabbedPane.addTab("Chat", createChatPanel())
        tabbedPane.addTab("Web View", createWebViewPlaceholder())
        tabbedPane.addTab("Context", createContextPanel())
        
        add(tabbedPane, BorderLayout.CENTER)
        
        // 初始化 WebView（如果 JCEF 可用）
        if (isJcefAvailable()) {
            webViewPanel = createWebViewPanel()
            tabbedPane.setComponentAt(1, webViewPanel)
        }
    }
    
    /**
     * 创建聊天页面
     */
    private fun createChatPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            
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
                
                add(JScrollPane(inputArea), BorderLayout.CENTER)
                
                val buttonPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    
                    add(sendButton)
                    add(Box.createHorizontalStrut(10))
                    add(streamingLabel)
                }
                add(buttonPanel, BorderLayout.SOUTH)
            }
            
            add(chatScrollPanel, BorderLayout.CENTER)
            add(inputPanel, BorderLayout.SOUTH)
            
            // 绑定事件
            setupChatListeners()
        }
    }
    
    /**
     * 创建上下文页面
     */
    private fun createContextPanel(): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            
            val headerLabel = JLabel("Context Items").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 14f)
            }
            
            val contextScrollPane = JBScrollPane(contextListView)
            
            val buttonPanel = JPanel().apply {
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
    
    /**
     * 显示上下文项的右键菜单
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
    
    /**
     * 创建 WebView 占位符（JCEF 不可用时）
     */
    private fun createWebViewPlaceholder(): JComponent {
        return JPanel(BorderLayout()).apply {
            val label = JLabel("WebView not available. Enable JCEF to use embedded browser.")
            label.horizontalAlignment = SwingConstants.CENTER
            add(label, BorderLayout.CENTER)
        }
    }
    
    /**
     * 创建 WebView 面板（使用 JCEF）
     */
    private fun createWebViewPanel(): Component {
        try {
            // 使用反射动态加载 JCEF，避免硬依赖
            val jcefHandlerClass = Class.forName("com.intellij.jcef.JBCefApp")
            val jcefClientClass = Class.forName("com.intellij.jcef.JBCefClient")
            val jbCefBrowserClass = Class.forName("com.intellij.jcef.JBCefBrowser")
            
            // 检查 JCEF 是否支持
            val isSupportedMethod = jcefHandlerClass.getMethod("isSupported")
            val isSupported = isSupportedMethod.invoke(null) as Boolean
            
            if (!isSupported) {
                return createWebViewPlaceholder()
            }
            
            // 创建浏览器实例
            val settings = com.aicopilot.settings.AIApplicationSettings.getInstance()
            val constructor = jbCefBrowserClass.getConstructor(String::class.java)
            val browser = constructor.newInstance(settings.webViewUrl)
            
            // 获取组件
            val componentMethod = jbCefBrowserClass.getMethod("getComponent")
            return componentMethod.invoke(browser) as Component
            
        } catch (e: Exception) {
            return createWebViewPlaceholder()
        }
    }
    
    /**
     * 检查 JCEF 是否可用
     */
    private fun isJcefAvailable(): Boolean {
        return try {
            val jcefClass = Class.forName("com.intellij.jcef.JBCefApp")
            val isSupportedMethod = jcefClass.getMethod("isSupported")
            isSupportedMethod.invoke(null) as Boolean
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * 设置聊天相关的事件监听器
     */
    private fun setupChatListeners() {
        sendButton.addActionListener {
            sendMessage()
        }
        
        inputArea.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) {
                // Auto-resize if needed
            }
            
            override fun removeUpdate(e: DocumentEvent?) {}
            override fun changedUpdate(e: DocumentEvent?) {}
        })
        
        // Enter 键发送
        inputArea.inputMap.put(
            javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, java.awt.event.InputEvent.CTRL_DOWN_MASK),
            "send"
        )
        inputArea.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                sendMessage()
            }
        })
    }
    
    /**
     * 发送消息
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
        
        // 清空输入框
        inputArea.text = ""
        
        // 滚动到底部
        chatListView.ensureIndexIsVisible(messageList.size - 1)
        
        // 发送请求并处理流式响应
        CoroutineScope(Dispatchers.Main).launch {
            streamingLabel.text = "Thinking..."
            
            val assistantMessage = ChatMessage(
                role = MessageRole.ASSISTANT,
                content = ""
            )
            messageList.addElement(assistantMessage)
            
            val messageIndex = messageList.size - 1
            
            try {
                chatService.sendChatStream(
                    messages = (0 until messageList.size - 1).map { messageList.getElementAt(it) },
                    contextItems = contextManager.getContextItems()
                ).collect { chunk ->
                    assistantMessage.copy(content = assistantMessage.content + chunk)
                    // 更新 UI
                    SwingUtilities.invokeLater {
                        val updatedMessage = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = assistantMessage.content + chunk
                        )
                        messageList.set(messageIndex, updatedMessage)
                        chatListView.ensureIndexIsVisible(messageIndex)
                    }
                }
            } catch (e: Exception) {
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
            }
        }
    }
    
    /**
     * 渲染单条消息为 HTML
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
    
    /**
     * 刷新上下文列表
     */
    fun refreshContextList() {
        contextListModel.clear()
        contextManager.getContextItems().forEach {
            contextListModel.addElement(it)
        }
    }
    
    /**
     * 添加上下文项并刷新
     */
    fun addContextItem(item: ContextItem) {
        contextListModel.addElement(item)
    }
}
