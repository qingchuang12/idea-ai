package com.aicopilot.settings

import com.aicopilot.model.ModelConfig
import com.aicopilot.model.ModelProtocol
import com.aicopilot.model.ModelProvider
import com.aicopilot.model.ModelSource
import com.aicopilot.service.ChatService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.table.AbstractTableModel

/**
 * 多模型管理表格面板（别名 / 厂商 / 模型 / 协议 / 启用）。
 * 在设置面板中嵌入，操作的是一份工作副本，apply 时回写。
 */
class ModelTablePanel : JPanel(BorderLayout()) {

    /** 工作副本（不直接改动 settings，apply 时同步） */
    val workingModels: MutableList<ModelConfig> = mutableListOf()
    var activeModelId: String = ""

    private val tableModel = ModelTableModel()
    private val table = JBTable(tableModel)

    init {
        table.rowHeight = JBUI.scale(24)
        table.columnModel.getColumn(COL_ENABLED).maxWidth = JBUI.scale(70)
        table.columnModel.getColumn(COL_ACTIVE).maxWidth = JBUI.scale(70)

        val decorator = ToolbarDecorator.createDecorator(table)
            .setAddAction { onAdd() }
            .setEditAction { onEdit() }
            .setRemoveAction { onRemove() }
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(
                "Set Active", "Set as active model", com.intellij.icons.AllIcons.Actions.Checked
            ) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = onSetActive()
            })
            .addExtraAction(object : com.intellij.openapi.actionSystem.AnAction(
                "Test", "Test connection", com.intellij.icons.AllIcons.Actions.Execute
            ) {
                override fun actionPerformed(e: com.intellij.openapi.actionSystem.AnActionEvent) = onTest()
            })
            .setEditActionUpdater { selectedEditable() != null }
            .setRemoveActionUpdater { selectedEditable() != null }

        add(decorator.createPanel(), BorderLayout.CENTER)
    }

    fun loadFrom(models: List<ModelConfig>, activeId: String) {
        workingModels.clear()
        // 仅 UI 来源可编辑，但 JSON 来源也展示（只读）
        models.forEach { workingModels.add(it.copy()) }
        activeModelId = activeId
        tableModel.fireTableDataChanged()
    }

    private fun selectedModel(): ModelConfig? {
        val row = table.selectedRow
        return if (row in workingModels.indices) workingModels[row] else null
    }

    private fun selectedEditable(): ModelConfig? = selectedModel()?.takeIf { it.isEditable() }

    private fun onAdd() {
        val model = ModelConfig(source = ModelSource.UI.name)
        val dialog = ModelEditDialog(model)
        if (dialog.showAndGet()) {
            workingModels.add(dialog.result)
            if (activeModelId.isBlank()) activeModelId = dialog.result.id
            tableModel.fireTableDataChanged()
        }
    }

    private fun onEdit() {
        val model = selectedEditable() ?: run {
            Messages.showInfoMessage("JSON 声明式模型为只读，请编辑对应 models.json 文件。", "AI Copilot")
            return
        }
        val row = table.selectedRow
        val dialog = ModelEditDialog(model.copy())
        if (dialog.showAndGet()) {
            workingModels[row] = dialog.result
            tableModel.fireTableRowsUpdated(row, row)
        }
    }

    private fun onRemove() {
        val model = selectedEditable() ?: return
        workingModels.remove(model)
        if (activeModelId == model.id) {
            activeModelId = workingModels.firstOrNull { it.enabled }?.id ?: ""
        }
        tableModel.fireTableDataChanged()
    }

    private fun onSetActive() {
        val model = selectedModel() ?: return
        activeModelId = model.id
        tableModel.fireTableDataChanged()
    }

    private fun onTest() {
        val model = selectedModel() ?: return
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { ChatService().testConnection(model) }
            result.fold(
                onSuccess = { Messages.showInfoMessage(it, "Test - ${model.alias}") },
                onFailure = { Messages.showErrorDialog(it.message ?: "Failed", "Test - ${model.alias}") }
            )
        }
    }

    private inner class ModelTableModel : AbstractTableModel() {
        private val columns = arrayOf("Active", "Alias", "Provider", "Model", "Protocol", "Enabled")
        override fun getRowCount(): Int = workingModels.size
        override fun getColumnCount(): Int = columns.size
        override fun getColumnName(column: Int): String = columns[column]
        override fun getColumnClass(columnIndex: Int): Class<*> = when (columnIndex) {
            COL_ACTIVE, COL_ENABLED -> java.lang.Boolean::class.java
            else -> String::class.java
        }
        override fun isCellEditable(rowIndex: Int, columnIndex: Int): Boolean {
            val m = workingModels[rowIndex]
            return (columnIndex == COL_ENABLED || columnIndex == COL_ACTIVE) && (columnIndex == COL_ACTIVE || m.isEditable())
        }
        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val m = workingModels[rowIndex]
            return when (columnIndex) {
                COL_ACTIVE -> m.id == activeModelId
                COL_ENABLED -> m.enabled
                1 -> m.alias
                2 -> m.provider
                3 -> m.modelName
                4 -> m.protocol
                else -> ""
            }
        }
        override fun setValueAt(aValue: Any?, rowIndex: Int, columnIndex: Int) {
            val m = workingModels[rowIndex]
            when (columnIndex) {
                COL_ACTIVE -> if (aValue == true) { activeModelId = m.id; fireTableDataChanged() }
                COL_ENABLED -> { m.enabled = aValue as? Boolean ?: true; fireTableRowsUpdated(rowIndex, rowIndex) }
            }
        }
    }

    companion object {
        private const val COL_ACTIVE = 0
        private const val COL_ENABLED = 5
    }
}

/**
 * 单个模型编辑对话框。
 */
class ModelEditDialog(private val source: ModelConfig) : DialogWrapper(true) {

    private val working = source.copy()
    val result: ModelConfig get() = working

    private val headersArea = JTextArea(working.headersJson, 3, 40)
    private val bodyArea = JTextArea(working.bodyTemplate, 5, 40)

    init {
        title = "Edit Model"
        init()
    }

    override fun createCenterPanel(): JComponent {
        return panel {
            row("Alias:") {
                textField().bindText({ working.alias }, { working.alias = it }).align(Align.FILL)
            }
            row("Provider:") {
                comboBox(ModelProvider.entries.map { it.displayName })
                    .bindItem(
                        { ModelProvider.entries.firstOrNull { p -> p.name == working.provider }?.displayName
                            ?: ModelProvider.CUSTOM.displayName },
                        { sel ->
                            val provider = ModelProvider.entries.firstOrNull { p -> p.displayName == sel } ?: ModelProvider.CUSTOM
                            working.provider = provider.name
                        }
                    )
            }
            row("Base URL:") {
                textField().bindText({ working.baseUrl }, { working.baseUrl = it }).align(Align.FILL)
            }
            row("API Key:") {
                passwordField().bindText({ working.apiKey }, { working.apiKey = it }).align(Align.FILL)
            }
            row("Model Name:") {
                textField().bindText({ working.modelName }, { working.modelName = it }).align(Align.FILL)
            }
            row("Protocol:") {
                comboBox(ModelProtocol.entries.map { it.name })
                    .bindItem({ working.protocol }, { working.protocol = it ?: ModelProtocol.OPENAI_COMPAT.name })
            }
            row {
                checkBox("Supports Function Calling")
                    .bindSelected({ working.supportsFunctionCalling }, { working.supportsFunctionCalling = it })
            }
            row {
                checkBox("Supports Vision (multimodal)")
                    .bindSelected({ working.supportsVision }, { working.supportsVision = it })
            }
            row {
                checkBox("Enabled").bindSelected({ working.enabled }, { working.enabled = it })
            }
            group("Custom Headers (JSON)") {
                row { cell(headersArea).align(Align.FILL) }
            }
            group("Request Body Template") {
                row { cell(bodyArea).align(Align.FILL) }
            }
        }
    }

    override fun doOKAction() {
        working.headersJson = headersArea.text.ifBlank { "{}" }
        working.bodyTemplate = bodyArea.text.ifBlank { ModelConfig.DEFAULT_BODY_TEMPLATE }
        super.doOKAction()
    }
}
