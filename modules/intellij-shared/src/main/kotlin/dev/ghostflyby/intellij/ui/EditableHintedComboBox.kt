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

public class EditableHintedComboBoxPresentation<T>(
    public val editorTextOf: (T) -> String,
    public val editorLeftHintOf: (T?) -> String = { "" },
)

public class EditableHintedComboBoxInputResolver<T>(
    public val findValueByEditorText: (List<T>, String) -> T? = { _, _ -> null },
    public val createInlineValue: ((String) -> T)? = null,
)

public class EditableHintedComboBox<T> internal constructor(
    private val presentation: EditableHintedComboBoxPresentation<T>,
    private val hintOverlayTextField: HintOverlayTextField = HintOverlayTextField(),
    private val comboBoxModel: DefaultComboBoxModel<T> = DefaultComboBoxModel(),
) : ComboBox<T>(comboBoxModel), ExtendableTextComponent by hintOverlayTextField {
    private val selectedItemListeners = mutableListOf<(T?) -> Unit>()
    private val editorTextListeners = mutableListOf<(String) -> Unit>()

    private var comboBoxEditorDelegate: BasicComboBoxEditor? = null
    private var inputResolver: EditableHintedComboBoxInputResolver<T> = EditableHintedComboBoxInputResolver(
        findValueByEditorText = { items, text ->
            items.firstOrNull { presentation.editorTextOf(it) == text }
        },
    )

    public var items: List<T> = emptyList()
        set(value) {
            if (field == value) {
                return
            }
            field = value
            replaceModelItems()
            syncSelectionFromCurrentState(
                preserveEditorText = hintOverlayTextField.hasFocus() && isUserEditingText,
                keepCurrentSelectionWhenUnresolved = true,
            )
        }

    public var editorText: String
        get() = hintOverlayTextField.text
        set(value) {
            if (hintOverlayTextField.text == value) {
                return
            }
            isUserEditingText = false
            applyEditorText(value)
        }

    private var isSyncingState = false
    private var hasPendingEditorSync = false
    private var isUserEditingText = false

    public val editorTextField: ExtendableTextField get() = hintOverlayTextField
    public var leftHint: String by hintOverlayTextField::trailingHint
    public var rightHint: String by hintOverlayTextField::rightHint
    public var isRightHintError: Boolean by hintOverlayTextField::isRightHintError


    init {
        this.isSwingPopup = false
        hintOverlayTextField.border = null

        comboBoxEditorDelegate = object : BasicComboBoxEditor() {
            override fun createEditorComponent(): JTextField {
                return hintOverlayTextField
            }

            override fun setItem(anObject: Any?) {
                val item = asItem(anObject)
                isSyncingState = true
                try {
                    editor?.text = item?.let(presentation.editorTextOf).orEmpty()
                    leftHint = presentation.editorLeftHintOf(item)
                } finally {
                    isSyncingState = false
                }
            }

            override fun getItem(): Any {
                val text = editor?.text.orEmpty()
                val selected = asItem(selectedItem)
                return when {
                    selected != null && text == presentation.editorTextOf(selected) -> selected
                    else -> resolveItemByInput(text) ?: selected ?: text
                }
            }
        }

        super.setEditor(comboBoxEditorDelegate)
        isEditable = true

        hintOverlayTextField.addFocusListener(
            object : FocusAdapter() {
                override fun focusLost(event: FocusEvent?) {
                    if (!isUserEditingText) {
                        return
                    }
                    isUserEditingText = false
                    commitEditorText()
                }
            },
        )

        hintOverlayTextField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (isSyncingState) {
                        return
                    }
                    isUserEditingText = true
                    if (hasPendingEditorSync) {
                        return
                    }
                    hasPendingEditorSync = true
                    SwingUtilities.invokeLater {
                        hasPendingEditorSync = false
                        if (isSyncingState) {
                            return@invokeLater
                        }
                        syncSelectionFromCurrentState(
                            preserveEditorText = true,
                            keepCurrentSelectionWhenUnresolved = false,
                        )
                        notifyEditorTextChanged(hintOverlayTextField.text)
                    }
                }
            },
        )

        addActionListener {
            if (isSyncingState) {
                return@addActionListener
            }
            isUserEditingText = false
            notifyEditorTextChanged(hintOverlayTextField.text)
        }

        SwingUtilities.invokeLater {
            reinstallComboEditorIfNeeded()
        }
        replaceModelItems()
        syncSelectionFromCurrentState(preserveEditorText = false, keepCurrentSelectionWhenUnresolved = true)
    }

    override fun getPreferredSize(): Dimension {
        return comboBoxSizeOrFallback { super.getPreferredSize() }
    }

    override fun addNotify() {
        super.addNotify()
        reinstallComboEditorIfNeeded()
    }

    override fun setSelectedItem(anObject: Any?) {
        applySelectedItem(asItem(anObject), preserveEditorText = isUserEditingText)
    }

    public fun configureInputResolver(
        resolver: EditableHintedComboBoxInputResolver<T>,
    ): EditableHintedComboBox<T> {
        inputResolver = resolver
        syncSelectionFromCurrentState(
            preserveEditorText = hintOverlayTextField.hasFocus() && isUserEditingText,
            keepCurrentSelectionWhenUnresolved = true,
        )
        return this
    }

    internal fun whenSelectedItemChanged(
        parentDisposable: Disposable? = null,
        listener: (T?) -> Unit,
    ): EditableHintedComboBox<T> {
        selectedItemListeners += listener
        parentDisposable?.let {
            Disposer.register(it) {
                selectedItemListeners.remove(listener)
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

    private fun resolveItemByInput(text: String): T? {
        return inputResolver.findValueByEditorText(items, text)
            ?: inputResolver.createInlineValue?.invoke(text)
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

    private fun notifySelectedItemChanged(value: T?) {
        selectedItemListeners.toList().forEach { listener ->
            listener(value)
        }
    }

    private fun notifyEditorTextChanged(value: String) {
        editorTextListeners.toList().forEach { listener ->
            listener(value)
        }
    }

    private fun replaceModelItems() {
        isSyncingState = true
        try {
            comboBoxModel.removeAllElements()
            items.forEach(comboBoxModel::addElement)
        } finally {
            isSyncingState = false
        }
    }

    private fun applyEditorText(text: String) {
        val resolvedItem = resolveItemByInput(text)
        isSyncingState = true
        try {
            if (hintOverlayTextField.text != text) {
                hintOverlayTextField.text = text
            }
        } finally {
            isSyncingState = false
        }
        applySelectedItem(resolvedItem, preserveEditorText = true)
    }

    private fun commitEditorText() {
        syncSelectionFromCurrentState(preserveEditorText = false, keepCurrentSelectionWhenUnresolved = false)
        notifyEditorTextChanged(hintOverlayTextField.text)
    }

    private fun syncSelectionFromCurrentState(
        preserveEditorText: Boolean,
        keepCurrentSelectionWhenUnresolved: Boolean,
    ) {
        val text = hintOverlayTextField.text
        val resolvedItem = resolveItemByInput(text)
        if (resolvedItem == null && !keepCurrentSelectionWhenUnresolved) {
            applySelectedItem(null, preserveEditorText = true)
            return
        }
        val targetItem = resolvedItem ?: asItem(super.getSelectedItem())
        applySelectedItem(targetItem, preserveEditorText = preserveEditorText)
    }

    private fun applySelectedItem(item: T?, preserveEditorText: Boolean) {
        val previousItem = asItem(super.getSelectedItem())
        val currentText = hintOverlayTextField.text
        val itemText = item?.let(presentation.editorTextOf).orEmpty()
        val shouldUpdateText = !preserveEditorText && currentText != itemText
        if (previousItem == item && !shouldUpdateText && leftHint == presentation.editorLeftHintOf(item)) {
            return
        }

        isSyncingState = true
        try {
            if (previousItem != item) {
                super.setSelectedItem(item)
            }
            if (preserveEditorText) {
                if (hintOverlayTextField.text != currentText) {
                    hintOverlayTextField.text = currentText
                }
            } else if (hintOverlayTextField.text != itemText) {
                hintOverlayTextField.text = itemText
            }
            leftHint = presentation.editorLeftHintOf(item)
        } finally {
            isSyncingState = false
        }

        if (previousItem != item) {
            notifySelectedItemChanged(item)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun asItem(value: Any?): T? {
        return value as? T
    }
}

public fun <T> createEditableHintedComboBox(
    presentation: EditableHintedComboBoxPresentation<T>,
): EditableHintedComboBox<T> {
    return EditableHintedComboBox(presentation = presentation)
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
