package com.aicopilot.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBUI
import javax.swing.JComponent

/**
 * AI Copilot 设置界面。
 * 顶部为多模型 BYOK 管理表格，其后为驱动模式、WebView、本地模型、高级设置与功能开关。
 */
class AIConfigurable : Configurable {

    private val settings = AIApplicationSettings.getInstance()

    private val modelPanel = ModelTablePanel()
    private var dialogPanel: DialogPanel? = null

    override fun getDisplayName(): String = "AI Copilot"

    override fun createComponent(): JComponent {
        settings.migrateLegacyIfNeeded()
        modelPanel.loadFrom(settings.models, settings.activeModelId)
        modelPanel.preferredSize = JBUI.size(600, 180)

        val dp = panel {
            group("Models (BYOK)") {
                row {
                    cell(modelPanel).align(Align.FILL)
                }
                row {
                    comment(
                        "支持双路径配置：此表格进行可视化管理；也可在项目根目录或 ~/.idea-ai 下放置 " +
                        "<code>models.json</code> 进行声明式配置（自动加载，聊天面板可选）。兼容 OpenAI 协议及主流国产模型。"
                    )
                }
            }

            group("Driver Mode") {
                row("Driver Mode:") {
                    comboBox(AIApplicationSettings.DriverMode.entries.map { it.name })
                        .bindItem(
                            { settings.driverMode },
                            { settings.driverMode = it ?: AIApplicationSettings.DriverMode.NATIVE_API.name }
                        )
                }
                row {
                    comment("NATIVE_API 使用上方激活模型（多模型 BYOK）；LOCAL_OLLAMA 使用本地模型配置。")
                }
            }

            group("Local Model Configuration") {
                row("Local Model URL:") {
                    textField().bindText({ settings.localModelUrl }, { settings.localModelUrl = it })
                }
                row("Local Model Name:") {
                    textField().bindText({ settings.localModelName }, { settings.localModelName = it })
                }
            }

            group("Advanced Settings") {
                row("Max Context Length:") {
                    textField().bindIntText({ settings.maxContextLength }, { settings.maxContextLength = it })
                }
                row("Request Timeout (seconds):") {
                    textField().bindIntText({ settings.requestTimeoutSeconds }, { settings.requestTimeoutSeconds = it })
                }
            }

            group("Features") {
                row {
                    checkBox("Enable inline code completion")
                        .bindSelected({ settings.enableInlineCompletion }, { settings.enableInlineCompletion = it })
                }
                row {
                    checkBox("Enable Agent (tool calling)")
                        .bindSelected({ settings.enableAgent }, { settings.enableAgent = it })
                }
                row {
                    checkBox("Require confirmation before file write / terminal")
                        .bindSelected({ settings.agentRequireConfirmation }, { settings.agentRequireConfirmation = it })
                }
                row {
                    checkBox("Enable project rules (.rules)")
                        .bindSelected({ settings.enableRules }, { settings.enableRules = it })
                }
                row {
                    checkBox("Enable MCP integration")
                        .bindSelected({ settings.enableMcp }, { settings.enableMcp = it })
                }
                row {
                    checkBox("Enable persistent memory")
                        .bindSelected({ settings.enableMemory }, { settings.enableMemory = it })
                }
            }

            group("AI Copilot Options") {
                row {
                    checkBox("Enable Ask mode (smart chat / AI expert)")
                        .bindSelected({ settings.enableSmartChat }, { settings.enableSmartChat = it })
                }
                row {
                    checkBox("Enable Next Edit Suggestion (NES)")
                        .bindSelected({ settings.enableNes }, { settings.enableNes = it })
                }
                row {
                    checkBox("Auto-load current open file into chat context")
                        .bindSelected({ settings.autoLoadOpenFile }, { settings.autoLoadOpenFile = it })
                }
                row("Reply language:") {
                    comboBox(listOf("Auto", "中文", "English"))
                        .bindItem({ settings.replyLanguage }, { settings.replyLanguage = it ?: "Auto" })
                }
                row {
                    checkBox("Auto-load built-in vendor models (OpenAI/DeepSeek/…)")
                        .bindSelected({ settings.autoUpdateBuiltinModels }, { settings.autoUpdateBuiltinModels = it })
                }
                row("HTTP Proxy Host:") {
                    textField().bindText({ settings.httpProxyHost }, { settings.httpProxyHost = it })
                }
                row("HTTP Proxy Port:") {
                    textField().bindIntText({ settings.httpProxyPort }, { settings.httpProxyPort = it })
                }
            }
        }
        dialogPanel = dp
        return JBScrollPane(dp)
    }

    override fun isModified(): Boolean {
        val dpModified = dialogPanel?.isModified() ?: false
        return dpModified || modelsModified()
    }

    private fun modelsModified(): Boolean {
        if (modelPanel.activeModelId != settings.activeModelId) return true
        val working = modelPanel.workingModels.filter { it.isEditable() }
        if (working.size != settings.models.size) return true
        return working.zip(settings.models).any { (a, b) -> a != b }
    }

    override fun apply() {
        dialogPanel?.apply()
        settings.models.clear()
        settings.models.addAll(modelPanel.workingModels.filter { it.isEditable() }.map { it.copy() })
        settings.activeModelId = modelPanel.activeModelId
    }

    override fun reset() {
        dialogPanel?.reset()
        modelPanel.loadFrom(settings.models, settings.activeModelId)
    }

    override fun disposeUIResources() {
        dialogPanel = null
    }
}
