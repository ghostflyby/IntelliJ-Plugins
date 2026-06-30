/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.intellij.ui

import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtilities
import java.awt.*
import java.awt.geom.Rectangle2D
import javax.swing.ComboBoxModel
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.plaf.basic.BasicComboBoxEditor
import kotlin.math.ceil

public interface EditableHintedComboBoxAdapter<T> {
    public fun text(t: T): String

    public fun fromText(
        text: String,
    ): T?

    public fun leftHint(t: T?): String = ""
}


public class EditableHintedComboBox<E>(
    model: ComboBoxModel<E> = DefaultComboBoxModel(),
    public var adapter: EditableHintedComboBoxAdapter<E>? = null,
) : ComboBox<E>(model) {
    init {
        isSwingPopup = false
        isEditable = true

        val editor = object : BasicComboBoxEditor() {

            protected override fun createEditorComponent(): JTextField {
                return HintOverlayTextField().apply { border = null }
            }

            override fun setItem(anObject: Any?) {
                val adapter = adapter
                if (anObject != null && adapter != null) {
                    @Suppress("UNCHECKED_CAST")
                    editor.text = adapter.text(anObject as E)
                } else super.setItem(anObject)
            }

            override fun getItem(): Any? {
                val adapter = adapter
                return if (adapter != null)
                    adapter.fromText(editor.text)
                else
                    super.item
            }

        }

        super.setEditor(editor)
    }

    public val editorTextField: ExtendableTextField get() = hintOverlayTextField
    public var leftHint: String by hintOverlayTextField::trailingHint
    public var rightHint: String by hintOverlayTextField::rightHint

    private val hintOverlayTextField: HintOverlayTextField
        get() {
            return editor.editorComponent as HintOverlayTextField
        }


    override fun getPreferredSize(): Dimension {
        return comboBoxSizeOrFallback { super.getPreferredSize() }
    }

    private fun comboBoxSizeOrFallback(sizeProvider: () -> Dimension): Dimension {
        return try {
            sizeProvider()
        } catch (_: NullPointerException) {
            val editorSize = hintOverlayTextField.preferredSize
            val insets = insets
            Dimension(
                editorSize.width + insets.left + insets.right + JBUI.scale(32),
                editorSize.height + insets.top + insets.bottom,
            )
        }
    }

}

internal class HintOverlayTextField : ExtendableTextField() {
    private companion object {
        const val OUTLINE_PROPERTY = "JComponent.outline"
        const val ERROR_OUTLINE = "error"
    }

    private var trailingHintValue: String? = null
    private var rightHintValue: String? = null

    private val gap = JBUI.scale(6)
    private val outerGap = JBUI.scale(8)

    var trailingHint: String
        get() = trailingHintValue.orEmpty()
        set(value) {
            trailingHintValue = value
            revalidate()
            repaint()
        }

    var rightHint: String
        get() = rightHintValue.orEmpty()
        set(value) {
            rightHintValue = value
            updateMargins()
            revalidate()
            repaint()
        }

    init {
        updateMargins()
        addPropertyChangeListener(OUTLINE_PROPERTY) {
            repaint()
        }
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, size.height)
    }

    private fun textWidth(text: String): Int {
        if (text.isEmpty()) {
            return 0
        }
        val metrics = getFontMetrics(font)
        return metrics.stringWidth(text)
    }

    private fun updateMargins() {
        val rightText = rightHint
        val rightExtensionWidth = rightExtensionReservedWidth()
        val rightReserved = if (rightText.isEmpty()) {
            outerGap + rightExtensionWidth
        } else {
            outerGap + rightExtensionWidth + gap + textWidth(rightText)
        }
        margin = JBUI.insets(0, outerGap, 0, rightReserved)
    }

    private fun rightExtensionReservedWidth(): Int {
        val rightExtensions = runCatching { extensions }
            .getOrNull()
            .orEmpty()
            .filterNot { it.isIconBeforeText }
        if (rightExtensions.isEmpty()) {
            return 0
        }
        return rightExtensions.sumOf { extension ->
            extension.buttonSize?.width ?: extension.preferredSpace
        }
    }

    override fun setFont(font: Font?) {
        super.setFont(font)
        if (font != null) {
            updateMargins()
        }
    }

    override fun setExtensions(vararg extensions: ExtendableTextComponent.Extension) {
        super.setExtensions(*extensions)
        updateMargins()
    }

    override fun addExtension(extension: ExtendableTextComponent.Extension) {
        super.addExtension(extension)
        updateMargins()
    }

    override fun removeExtension(extension: ExtendableTextComponent.Extension) {
        super.removeExtension(extension)
        updateMargins()
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)

        val trailingText = trailingHintValue.orEmpty()
        val rightText = rightHintValue.orEmpty()
        if (trailingText.isEmpty() && rightText.isEmpty()) {
            return
        }

        val g2 = graphics.create() as? Graphics2D ?: return
        try {
            g2.font = font

            val metrics: FontMetrics = g2.getFontMetrics(font)
            val baseline = (height - metrics.height) / 2 + metrics.ascent
            val rightExtensionWidth = rightExtensionReservedWidth()
            val rightHintX = if (rightText.isEmpty()) {
                Int.MIN_VALUE
            } else {
                width - outerGap - rightExtensionWidth - textWidth(rightText)
            }

            if (trailingText.isNotEmpty()) {
                g2.color = JBColor.namedColor(
                    "TextField.inactiveForeground",
                    JBColor.namedColor("Component.infoForeground", JBColor.GRAY),
                )
                val endX = textEndX()
                val hintX = endX + gap
                val rightLimit = if (rightText.isEmpty()) {
                    width - outerGap - rightExtensionWidth - gap
                } else {
                    rightHintX - gap
                }

                if (hintX < rightLimit) {
                    UIUtilities.drawString(this, g2, trailingText, hintX, baseline)
                }
            }

            if (rightText.isNotEmpty()) {
                g2.color = if (getClientProperty(OUTLINE_PROPERTY) == ERROR_OUTLINE) {
                    JBColor.namedColor("Label.errorForeground", JBColor.RED)
                } else {
                    JBColor.namedColor(
                        "TextField.inactiveForeground",
                        JBColor.namedColor("Component.infoForeground", JBColor.GRAY),
                    )
                }
                UIUtilities.drawString(this, g2, rightText, rightHintX, baseline)
            }
        } finally {
            g2.dispose()
        }
    }

    private fun textEndX(): Int {
        return runCatching {
            val shape: Rectangle2D = modelToView2D(document.length)
            ceil(shape.x).toInt()
        }.getOrElse {
            insets.left + textWidth(text.orEmpty())
        }
    }
}
