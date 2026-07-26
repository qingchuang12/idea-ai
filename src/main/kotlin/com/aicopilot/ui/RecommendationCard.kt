package com.aicopilot.ui

import com.aicopilot.model.Recommendation
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 结果推荐卡片：任务完成后浮现的圆角玻璃拟态卡片，点击触发对应快捷动作。
 */
class RecommendationCard(
    private val recommendation: Recommendation,
    private val onClick: (Recommendation) -> Unit
) : JPanel() {

    private var hovered = false

    private val accent = JBColor(Color(0x4F, 0x7C, 0xFF), Color(0x6E, 0x9B, 0xFF))
    private val cardBg = JBColor(Color(0x2D, 0x2F, 0x34), Color(0x2D, 0x2F, 0x34))
    private val cardBgHover = JBColor(Color(0x35, 0x38, 0x40), Color(0x35, 0x38, 0x40))

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(10, 12)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        maximumSize = Dimension(Int.MAX_VALUE, 64)

        val textPanel = JPanel().apply {
            isOpaque = false
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(JLabel(recommendation.title).apply {
                foreground = JBColor(Color(0xE6, 0xE6, 0xE6), Color(0xE6, 0xE6, 0xE6))
                font = font.deriveFont(java.awt.Font.BOLD, 13f)
            })
            add(JLabel(recommendation.description).apply {
                foreground = JBColor(Color(0xA8, 0xAB, 0xB2), Color(0xA8, 0xAB, 0xB2))
                font = font.deriveFont(12f)
                border = BorderFactory.createEmptyBorder(3, 0, 0, 0)
            })
        }
        add(textPanel, BorderLayout.CENTER)

        add(JLabel("›").apply {
            foreground = accent
            font = font.deriveFont(java.awt.Font.BOLD, 20f)
            border = JBUI.Borders.emptyRight(4)
        }, BorderLayout.EAST)

        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent?) { hovered = true; repaint() }
            override fun mouseExited(e: MouseEvent?) { hovered = false; repaint() }
            override fun mouseClicked(e: MouseEvent?) { onClick(recommendation) }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = if (hovered) cardBgHover else cardBg
        g2.fillRoundRect(0, 0, width - 1, height - 1, 14, 14)
        // 左侧强调条
        g2.color = accent
        g2.fillRoundRect(0, 0, 4, height - 1, 4, 4)
        g2.dispose()
        super.paintComponent(g)
    }
}
