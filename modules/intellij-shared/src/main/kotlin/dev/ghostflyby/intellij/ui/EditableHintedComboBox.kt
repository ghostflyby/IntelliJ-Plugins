/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.intellij.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtilities
import java.awt.*
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.geom.Rectangle2D
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.plaf.basic.BasicComboBoxEditor
import kotlin.math.ceil

public interface EditableHintedComboBoxItem {
    public val key: String
    public val displayName: String
    public val editorText: String
    public val trailingHint: String
}

public class EditableHintedComboBox<T : EditableHintedComboBoxItem> internal constructor(
    private val hintOverlayTextField: HintOverlayTextField = HintOverlayTextField(),
    private val comboBoxModel: DefaultComboBoxModel<T> = DefaultComboBoxModel(),
) : ComboBox<T>(comboBoxModel), ExtendableTextComponent by hintOverlayTextField {
    private val selectedItemKeyListeners = mutableListOf<(String?) -> Unit>()
    private val editorTextListeners = mutableListOf<(String) -> Unit>()

    private var comboBoxEditorDelegate: BasicComboBoxEditor? = null
    private var createInlineItemHandler: ((String) -> T)? = null
    private var findItemByInputHandler: (List<T>, String) -> T? = { items, text ->
        items.firstOrNull { it.editorText == text }
    }

    public var items: List<T> = emptyList()
        set(value) {
            if (field == value) {
                return
            }
            field = value
            refreshFromState()
        }

    public var selectedItemKey: String? = null
        set(value) {
            if (field == value) {
                return
            }
            field = value
            refreshFromState()
        }

    public var editorText: String = ""
        set(value) {
            if (field == value) {
                return
            }
            field = value
            refreshFromState()
        }

    private var isRefreshing = false
    private var isUpdatingEditorPresentation = false
    private var hasPendingInputSync = false
    private var isUserEditingText = false

    public val editorTextField: ExtendableTextField get() = hintOverlayTextField
    public var leftHint: String by hintOverlayTextField::trailingHint
    public var rightHint: String by hintOverlayTextField::rightHint
    public var isRightHintError: Boolean by hintOverlayTextField::isRightHintError


    init {
        hintOverlayTextField.border = null

        comboBoxEditorDelegate = object : BasicComboBoxEditor() {
            override fun createEditorComponent(): JTextField {
                return hintOverlayTextField
            }

            override fun setItem(anObject: Any?) {
                val item = anObject as? T
                isUpdatingEditorPresentation = true
                try {
                    when (item) {
                        null -> {
                            editor?.text = ""
                            leftHint = ""
                        }

                        else -> {
                            editor?.text = item.editorText
                            leftHint = item.trailingHint
                        }
                    }
                } finally {
                    isUpdatingEditorPresentation = false
                }
            }

            override fun getItem(): Any {
                val text = editor?.text.orEmpty()
                val selected = selectedItem as? T
                return when {
                    selected != null && text == selected.editorText -> selected
                    else -> resolveItemByInput(text) ?: selected ?: text
                }
            }
        }

        super.setEditor(comboBoxEditorDelegate)
        isEditable = true
        renderer = createRenderer()

        hintOverlayTextField.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(event: FocusEvent?) {
                    isUserEditingText = false
                    refreshFromState()
                }
            },
        )

        hintOverlayTextField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (isRefreshing || isUpdatingEditorPresentation) {
                        return
                    }
                    isUserEditingText = true
                    if (hasPendingInputSync) {
                        return
                    }
                    hasPendingInputSync = true
                    SwingUtilities.invokeLater {
                        hasPendingInputSync = false
                        if (isRefreshing || isUpdatingEditorPresentation) {
                            return@invokeLater
                        }
                        notifyEditorTextChanged(hintOverlayTextField.text)
                    }
                }
            },
        )

        addActionListener {
            if (isRefreshing) {
                return@addActionListener
            }
            isUserEditingText = false
            val item = selectedItem as? T ?: return@addActionListener
            leftHint = item.trailingHint
            notifySelectedItemKeyChanged(item.key)
            notifyEditorTextChanged(item.editorText)
        }

        SwingUtilities.invokeLater {
            reinstallComboEditorIfNeeded()
        }
        refreshFromState()
    }

    override fun getPreferredSize(): Dimension {
        return comboBoxSizeOrFallback { super.getPreferredSize() }
    }

    override fun addNotify() {
        super.addNotify()
        reinstallComboEditorIfNeeded()
    }

    public fun configureInputResolution(
        createInlineItem: (String) -> T,
        findItemByInput: (List<T>, String) -> T? = { items, text ->
            items.firstOrNull { it.editorText == text }
        },
    ): EditableHintedComboBox<T> {
        createInlineItemHandler = createInlineItem
        findItemByInputHandler = findItemByInput
        refreshFromState()
        return this
    }

    public fun whenSelectedItemKeyChanged(
        parentDisposable: Disposable? = null,
        listener: (String?) -> Unit,
    ): EditableHintedComboBox<T> {
        selectedItemKeyListeners += listener
        parentDisposable?.let {
            Disposer.register(it) {
                selectedItemKeyListeners.remove(listener)
            }
        }
        return this
    }

    public fun whenEditorTextChanged(
        parentDisposable: Disposable? = null,
        listener: (String) -> Unit,
    ): EditableHintedComboBox<T> {
        editorTextListeners += listener
        parentDisposable?.let {
            Disposer.register(it) {
                editorTextListeners.remove(listener)
            }
        }
        return this
    }

    private fun itemByKey(key: String?): T? {
        if (key.isNullOrBlank()) {
            return null
        }
        return items.firstOrNull { it.key == key }
    }

    private fun resolveItemByInput(text: String): T? {
        return findItemByInputHandler(items, text)
            ?: createInlineItemHandler?.invoke(text)
    }

    private fun resolveSelectedItem(): T? {
        return itemByKey(selectedItemKey)
            ?: createInlineItemHandler?.invoke(editorText)
    }

    private fun reinstallComboEditorIfNeeded() {
        val editorDelegate = comboBoxEditorDelegate ?: return
        val editable = isEditable
        if (editable) {
            super.setEditable(false)
        }
        super.setEditor(editorDelegate)
        super.setEditable(editable)
        revalidate()
        repaint()
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

    private fun notifySelectedItemKeyChanged(value: String?) {
        if (selectedItemKey == value) {
            return
        }
        selectedItemKey = value
        selectedItemKeyListeners.toList().forEach { listener ->
            listener(value)
        }
    }

    private fun notifyEditorTextChanged(value: String) {
        if (editorText == value) {
            return
        }
        editorText = value
        editorTextListeners.toList().forEach { listener ->
            listener(value)
        }
    }

    private fun refreshFromState() {
        if (hintOverlayTextField.hasFocus() && isUserEditingText) {
            val selectedItem = resolveSelectedItem()
            isUpdatingEditorPresentation = true
            try {
                leftHint = selectedItem?.trailingHint.orEmpty()
            } finally {
                isUpdatingEditorPresentation = false
            }
            return
        }

        isRefreshing = true
        try {
            comboBoxModel.removeAllElements()
            items.forEach(comboBoxModel::addElement)

            val selectedItem = resolveSelectedItem()
            this.selectedItem = selectedItem

            isUpdatingEditorPresentation = true
            try {
                leftHint = selectedItem?.trailingHint.orEmpty()
            } finally {
                isUpdatingEditorPresentation = false
            }
        } finally {
            isRefreshing = false
        }
    }

    @Suppress("UnstableApiUsage")
    private fun createRenderer() = listCellRenderer<T> {
        // The popup only needs the display name, and the DSL renderer keeps this concise.
        text(value.displayName)
    }
}

public fun <T : EditableHintedComboBoxItem> createEditableHintedComboBox(
): EditableHintedComboBox<T> {
    return EditableHintedComboBox()
}

internal class HintOverlayTextField : ExtendableTextField() {
    private var trailingHintValue: String? = null
    private var rightHintValue: String? = null
    private var rightHintErrorValue: Boolean = false

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

    var isRightHintError: Boolean
        get() = rightHintErrorValue
        set(value) {
            rightHintErrorValue = value
            repaint()
        }

    init {
        updateMargins()
    }

    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        val extra = textWidth(trailingHint) + textWidth(rightHint) + gap * 3 + rightExtensionReservedWidth()
        return Dimension(size.width + extra, size.height)
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
                g2.color = if (rightHintErrorValue) {
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
