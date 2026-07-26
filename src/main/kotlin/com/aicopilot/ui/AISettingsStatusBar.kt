package com.aicopilot.ui

import com.aicopilot.settings.AIApplicationSettings
import com.aicopilot.settings.AIConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBLabel
import com.intellij.util.Consumer
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JPanel
import javax.swing.BoxLayout

/**
 * 右下角设置入口：在状态栏（RIGHT）显示一个 AI 入口，
 * 点击弹出快捷面板，可一键启停「代码补全 / 行间预测（NES）」，并跳转高级设置。
 */
class AISettingsStatusBarFactory : StatusBarWidgetFactory {

    override fun getId(): String = "AICopilotSettings"

    override fun getDisplayName(): String = "AI Copilot Settings"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project): StatusBarWidget = AISettingsWidget(project)

    override fun disposeWidget(widget: StatusBarWidget) = widget.dispose()

    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true
}

class AISettingsWidget(private val project: Project) : StatusBarWidget, StatusBarWidget.TextPresentation {

    private val settings = AIApplicationSettings.getInstance()

    override fun ID(): String = "AICopilotSettings"

    override fun getText(): String = "AI"

    override fun getTooltipText(): String = "AI Copilot 设置：代码补全 / 行间预测 / 高级设置"

    override fun getAlignment(): Float = 0f

    override fun getClickConsumer(): Consumer<MouseEvent> = Consumer { showPopup() }

    override fun install(statusBar: StatusBar) {}

    override fun dispose() {}

    private fun showPopup() {
        val inline = JCheckBox("启用代码补全", settings.enableInlineCompletion).apply {
            addItemListener { settings.enableInlineCompletion = isSelected }
        }
        val nes = JCheckBox("启用行间预测（NES）", settings.enableNes).apply {
            addItemListener { settings.enableNes = isSelected }
        }
        val openBtn = JButton("打开设置…").apply {
            addActionListener {
                popupRef?.cancel()
                ShowSettingsUtil.getInstance().showSettingsDialog(project, AIConfigurable::class.java)
            }
        }
        val openKb = JButton("打开 AI 聊天").apply {
            addActionListener {
                popupRef?.cancel()
                ToolWindowManager.getInstance(project).getToolWindow("AI Copilot")?.show()
            }
        }
        val panel = JPanel().apply {
            border = JBUI.Borders.empty(10)
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(inline)
            add(nes)
            add(JPanel(BorderLayout()).apply { add(openKb, BorderLayout.WEST); add(openBtn, BorderLayout.EAST) })
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, inline)
            .setTitle("AI Copilot")
            .setResizable(false)
            .createPopup()
        popupRef = popup
        popup.showInFocusCenter()
    }

    private var popupRef: com.intellij.openapi.ui.popup.JBPopup? = null
}
