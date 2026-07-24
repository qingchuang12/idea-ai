package com.aicopilot.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.*
import javax.swing.JComboBox
import javax.swing.JTextField

/**
 * AI Copilot 设置界面
 */
class AIConfigurable : Configurable {
    
    private val settings = AIApplicationSettings.getInstance()
    
    private lateinit var driverModeCombo: JComboBox<String>
    private lateinit var baseUrlField: JTextField
    private lateinit var apiKeyField: JTextField
    private lateinit var modelNameField: JTextField
    private lateinit var webViewUrlField: JTextField
    private lateinit var localModelUrlField: JTextField
    private lateinit var localModelNameField: JTextField
    private lateinit var customHeadersArea: JBTextArea
    private lateinit var requestBodyTemplateArea: JBTextArea
    private lateinit var maxContextLengthField: JTextField
    private lateinit var timeoutField: JTextField
    
    override fun getDisplayName(): String = "AI Copilot"
    
    override fun createPanel(): DialogPanel {
        return panel {
            group("Driver Configuration") {
                row("Driver Mode:") {
                    driverModeCombo = comboBox(
                        AIApplicationSettings.DriverMode.values().map { it.name }
                    ).bindItem(
                        { settings.driverMode },
                        { settings.driverMode = it ?: AIApplicationSettings.DriverMode.NATIVE_API.name }
                    ).component
                }
                
                // 原生 API 配置
                row("Base URL:") {
                    baseUrlField = textField().bindText(
                        { settings.baseUrl },
                        { settings.baseUrl = it }
                    ).component
                }
                
                row("API Key:") {
                    apiKeyField = passwordField().bindText(
                        { settings.apiKey },
                        { settings.apiKey = it }
                    ).component
                }
                
                row("Model Name:") {
                    modelNameField = textField().bindText(
                        { settings.modelName },
                        { settings.modelName = it }
                    ).component
                }
                
                row("Custom Headers (JSON):") {
                    customHeadersArea = jTextArea(settings.customHeaders).applyToComponent {
                        lineWrap = true
                        wrapStyleWord = true
                    }.bindText(
                        { settings.customHeaders },
                        { settings.customHeaders = it }
                    ).component
                }
                
                row("Request Body Template:") {
                    requestBodyTemplateArea = jTextArea(settings.requestBodyTemplate).applyToComponent {
                        lineWrap = true
                        wrapStyleWord = true
                    }.bindText(
                        { settings.requestBodyTemplate },
                        { settings.requestBodyTemplate = it }
                    ).component
                }
            }
            
            group("WebView Configuration") {
                row("WebView URL:") {
                    webViewUrlField = textField().bindText(
                        { settings.webViewUrl },
                        { settings.webViewUrl = it }
                    ).component
                }
            }
            
            group("Local Model Configuration") {
                row("Local Model URL:") {
                    localModelUrlField = textField().bindText(
                        { settings.localModelUrl },
                        { settings.localModelUrl = it }
                    ).component
                }
                
                row("Local Model Name:") {
                    localModelNameField = textField().bindText(
                        { settings.localModelName },
                        { settings.localModelName = it }
                    ).component
                }
            }
            
            group("Advanced Settings") {
                row("Max Context Length:") {
                    maxContextLengthField = textField().bindInt(
                        { settings.maxContextLength },
                        { settings.maxContextLength = it }
                    ).component
                }
                
                row("Request Timeout (seconds):") {
                    timeoutField = textField().bindInt(
                        { settings.requestTimeoutSeconds },
                        { settings.requestTimeoutSeconds = it }
                    ).component
                }
            }
        }
    }
    
    override fun isModified(): Boolean {
        return true // Simplified - in production, compare with original values
    }
    
    override fun apply() {
        // Values are already bound via DSL
    }
}
