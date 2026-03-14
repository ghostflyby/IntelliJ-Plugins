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

package dev.ghostflyby.mill.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.components.fields.ExtendableTextComponent
import com.intellij.ui.components.fields.ExtendableTextField
import com.intellij.ui.dsl.listCellRenderer.listCellRenderer
import com.intellij.util.ui.JBUI
import dev.ghostflyby.mill.Bundle
import java.awt.Font
import java.awt.FontMetrics
import java.awt.Graphics
import java.awt.geom.Rectangle2D
import javax.swing.DefaultComboBoxModel
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.plaf.basic.BasicComboBoxEditor
import kotlin.math.ceil

internal fun createMillExecutableSelectorField(
    project: Project,
    viewModel: MillConfigurableViewModel,
    parentDisposable: Disposable,
): ComboBox<MillExecutableChoice> {
    val comboBoxModel = DefaultComboBoxModel<MillExecutableChoice>()
    val comboBox = ComboBox(comboBoxModel)
    var editorField: HintOverlayTextField? = null
    var isRefreshing = false
    var isUpdatingEditorPresentation = false

    fun choiceByKey(key: String?): MillExecutableChoice? {
        if (key.isNullOrBlank()) {
            return null
        }
        return (0 until comboBoxModel.size)
            .asSequence()
            .map(comboBoxModel::getElementAt)
            .firstOrNull { it.key == key }
    }

    fun choiceByText(text: String): MillExecutableChoice? {
        if (text.isBlank()) {
            return null
        }
        return (0 until comboBoxModel.size)
            .asSequence()
            .map(comboBoxModel::getElementAt)
            .firstOrNull { choice ->
                text == choice.editorText || text == choice.displayName || text == choice.manualPath
            }
    }

    fun rightHintText(): String = viewModel.executableVersionTextProperty.get()

    val browseExtension = ExtendableTextComponent.Extension.create(
        AllIcons.General.OpenDisk,
        AllIcons.General.OpenDiskHover,
        Bundle.message("settings.mill.executable.manual.title"),
    ) {
        FileChooser.chooseFile(
            FileChooserDescriptorFactory.singleFile()
                .withTitle(Bundle.message("settings.mill.executable.manual.title")),
            project,
            comboBox,
            null,
        ) { virtualFile ->
            comboBox.selectedItem = createInlineManualChoice(virtualFile.path)
        }
    }

    comboBox.isEditable = true

    comboBox.editor = object : BasicComboBoxEditor() {
        override fun createEditorComponent(): JTextField {
            val field = HintOverlayTextField().apply {
                addExtension(browseExtension)
                border = null
            }
            editorField = field
            return field
        }

        override fun setItem(anObject: Any?) {
            val field = editorField ?: return
            val item = anObject as? MillExecutableChoice
            isUpdatingEditorPresentation = true
            try {
                when (item) {
                    null -> {
                        editor?.text = ""
                        field.trailingHint = ""
                        field.rightHint = ""
                    }

                    else -> {
                        editor?.text = item.editorText
                        field.trailingHint = item.editorTrailingHint
                        field.rightHint = rightHintText()
                        field.isRightHintError = viewModel.executableStatusIsErrorProperty.get()
                    }
                }
            } finally {
                isUpdatingEditorPresentation = false
            }
        }

        override fun getItem(): Any {
            val text = editor?.text.orEmpty()
            val selected = comboBox.selectedItem as? MillExecutableChoice
            return when {
                selected?.source == MillExecutableSource.PROJECT_DEFAULT_SCRIPT && text == selected.editorText -> selected
                selected?.source == MillExecutableSource.PATH && text == selected.editorText -> selected
                else -> choiceByText(text) ?: createInlineManualChoice(text)
            }
        }
    }

    comboBox.renderer = listCellRenderer {
        text(value?.displayName.orEmpty())
    }

    val initializedEditorField = requireNotNull(editorField)

    initializedEditorField.document.addDocumentListener(
        object : DocumentAdapter() {
            override fun textChanged(event: DocumentEvent) {
                if (isRefreshing || isUpdatingEditorPresentation) {
                    return
                }
                viewModel.updateExecutableInput(initializedEditorField.text)
            }
        },
    )

    comboBox.addActionListener {
        if (isRefreshing) {
            return@addActionListener
        }
        val item = comboBox.selectedItem as? MillExecutableChoice ?: return@addActionListener
        initializedEditorField.trailingHint = item.editorTrailingHint
        initializedEditorField.rightHint = rightHintText()
        initializedEditorField.isRightHintError = viewModel.executableStatusIsErrorProperty.get()
        viewModel.selectExecutableChoice(item)
    }

    fun refreshFromModel() {
        isRefreshing = true
        try {
            val choices = viewModel.executableChoicesProperty.get()
            comboBoxModel.removeAllElements()
            choices.forEach(comboBoxModel::addElement)

            val selectedChoice = choiceByKey(viewModel.executableSelectedChoiceKeyProperty.get())
                ?: createInlineManualChoice(viewModel.executableInputTextProperty.get())
            comboBox.selectedItem = selectedChoice

            isUpdatingEditorPresentation = true
            try {
                initializedEditorField.trailingHint = selectedChoice.editorTrailingHint
                initializedEditorField.rightHint = rightHintText()
                initializedEditorField.isRightHintError = viewModel.executableStatusIsErrorProperty.get()
            } finally {
                isUpdatingEditorPresentation = false
            }
        } finally {
            isRefreshing = false
        }
    }

    viewModel.executableChoicesProperty.afterChange(parentDisposable) {
        refreshFromModel()
    }
    viewModel.executableSelectedChoiceKeyProperty.afterChange(parentDisposable) {
        refreshFromModel()
    }
    viewModel.executableInputTextProperty.afterChange(parentDisposable) {
        refreshFromModel()
    }
    viewModel.executableVersionTextProperty.afterChange(parentDisposable) {
        refreshFromModel()
    }
    viewModel.executableStatusIsErrorProperty.afterChange(parentDisposable) {
        refreshFromModel()
    }

    refreshFromModel()
    return comboBox
}

private fun createInlineManualChoice(text: String): MillExecutableChoice {
    return MillExecutableChoice(
        key = "manual:$text",
        displayName = text,
        detailText = null,
        source = MillExecutableSource.MANUAL,
        manualPath = text,
        tooltipText = text,
    )
}

private class HintOverlayTextField : ExtendableTextField() {
    private var trailingHintValue: String? = null
    private var rightHintValue: String? = null
    private var rightHintErrorValue: Boolean = false

    var trailingHint: String
        get() = trailingHintValue.orEmpty()
        set(value) {
            trailingHintValue = value
            updateMargins()
            repaint()
        }

    var rightHint: String
        get() = rightHintValue.orEmpty()
        set(value) {
            rightHintValue = value
            updateMargins()
            repaint()
        }

    var isRightHintError: Boolean
        get() = rightHintErrorValue
        set(value) {
            rightHintErrorValue = value
            repaint()
        }

    private val gap = JBUI.scale(6)
    private val outerGap = JBUI.scale(8)

    init {
        updateMargins()
    }

    private fun textWidth(text: String): Int {
        if (text.isEmpty()) {
            return 0
        }
        val metrics = getFontMetrics(font)
        return metrics.stringWidth(text)
    }

    private fun updateMargins() {
        val rightText = rightHintValue.orEmpty()
        val right = if (rightText.isEmpty()) {
            outerGap
        } else {
            outerGap + textWidth(rightText) + gap
        }
        margin = JBUI.insets(0, outerGap, 0, right)
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

        val g2 = graphics.create()
        try {
            g2.font = font

            val metrics: FontMetrics = g2.getFontMetrics(font)
            val baseline = (height - metrics.height) / 2 + metrics.ascent

            if (trailingText.isNotEmpty()) {
                g2.color = JBColor.namedColor(
                    "TextField.inactiveForeground",
                    JBColor.namedColor("Component.infoForeground", JBColor.GRAY),
                )
                val endX = textEndX()
                val hintX = endX + gap
                val rightLimit = width - insets.right - textWidth(rightText) - gap

                if (hintX < rightLimit) {
                    g2.drawString(trailingText, hintX, baseline)
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
                val textWidth = metrics.stringWidth(rightText)
                val x = width - insets.right - textWidth
                g2.drawString(rightText, x, baseline)
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
