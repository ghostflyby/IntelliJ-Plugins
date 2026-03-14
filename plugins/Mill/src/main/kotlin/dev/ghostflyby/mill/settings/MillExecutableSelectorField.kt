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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import dev.ghostflyby.mill.Bundle
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.ActionListener
import javax.swing.*
import javax.swing.event.DocumentEvent

internal class MillExecutableSelectorField(
    private val project: Project,
    private val model: MillConfigurableViewModel,
    parentDisposable: Disposable,
) : JPanel(BorderLayout(JBUI.scale(8), 0)) {
    private val comboBoxModel = DefaultComboBoxModel<MillExecutableChoice>()
    private val editor = ExecutableChoiceEditor()
    private val comboBox = ComboBox<MillExecutableChoice>(comboBoxModel).apply {
        isEditable = true
        editor = this@MillExecutableSelectorField.editor
        renderer = ExecutableChoiceRenderer()
        minimumSize = Dimension(JBUI.scale(360), preferredSize.height)
    }
    private val browseButton = JButton(AllIcons.General.OpenDisk).apply {
        toolTipText = Bundle.message("settings.mill.executable.manual.title")
        margin = JBInsets.create(0, 4)
        isFocusable = false
        border = BorderFactory.createEmptyBorder(JBUI.scale(2), JBUI.scale(4), JBUI.scale(2), JBUI.scale(4))
        addActionListener {
            val selectedFile = FileChooser.chooseFile(
                FileChooserDescriptorFactory.singleFile()
                    .withTitle(Bundle.message("settings.mill.executable.manual.title")),
                project,
                null,
            ) ?: return@addActionListener
            this@MillExecutableSelectorField.model.updateExecutableInput(selectedFile.path)
        }
    }
    private var isUpdatingFromModel = false

    init {
        add(comboBox, BorderLayout.CENTER)
        add(browseButton, BorderLayout.EAST)

        comboBox.addActionListener(
            ActionListener {
                if (isUpdatingFromModel) {
                    return@ActionListener
                }
                val selectedChoice = comboBox.selectedItem as? MillExecutableChoice ?: return@ActionListener
                model.selectExecutableChoice(selectedChoice)
            },
        )
        editor.textField.document.addDocumentListener(
            object : DocumentAdapter() {
                override fun textChanged(event: DocumentEvent) {
                    if (isUpdatingFromModel) {
                        return
                    }
                    model.updateExecutableInput(editor.textField.text)
                }
            },
        )

        model.executableChoicesProperty.afterChange(parentDisposable) {
            refreshFromModel()
        }
        model.executableInputTextProperty.afterChange(parentDisposable) {
            refreshFromModel()
        }
        model.executableSelectionToolTipProperty.afterChange(parentDisposable) {
            refreshFromModel()
        }
        model.executableVersionTextProperty.afterChange(parentDisposable) {
            refreshFromModel()
        }
        model.executableStatusTextProperty.afterChange(parentDisposable) {
            refreshFromModel()
        }
        model.executableStatusIsErrorProperty.afterChange(parentDisposable) {
            refreshFromModel()
        }

        refreshFromModel()
    }

    private fun refreshFromModel() {
        isUpdatingFromModel = true
        try {
            val choices = model.executableChoicesProperty.get()
            comboBoxModel.removeAllElements()
            choices.forEach(comboBoxModel::addElement)

            val editorText = model.executableInputTextProperty.get()
            val selectedChoice = choices.firstOrNull { editorText == it.editorText }
            comboBox.selectedItem = selectedChoice
            editor.textField.text = editorText
            editor.versionLabel.text = model.executableVersionTextProperty.get()
            editor.versionLabel.foreground = if (model.executableStatusIsErrorProperty.get()) {
                JBColor.RED
            } else {
                JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
            }
            val selectionTooltip = model.executableSelectionToolTipProperty.get()
            val statusTooltip = model.executableStatusTextProperty.get()
            comboBox.toolTipText = selectionTooltip.takeIf(String::isNotBlank) ?: statusTooltip
            editor.textField.toolTipText = selectionTooltip.takeIf(String::isNotBlank) ?: statusTooltip
            editor.versionLabel.toolTipText = statusTooltip
        } finally {
            isUpdatingFromModel = false
        }
    }
}

private class ExecutableChoiceEditor : ComboBoxEditor {
    val textField = JBTextField().apply {
        border = BorderFactory.createEmptyBorder(JBUI.scale(2), 0, JBUI.scale(2), JBUI.scale(4))
    }
    val versionLabel = JBLabel().apply {
        border = BorderFactory.createEmptyBorder(0, JBUI.scale(8), 0, 0)
    }
    private val panel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        isOpaque = false
        border = BorderFactory.createEmptyBorder(0, JBUI.scale(6), 0, JBUI.scale(6))
        add(textField, BorderLayout.CENTER)
        add(versionLabel, BorderLayout.EAST)
    }

    override fun getEditorComponent(): Component = panel

    override fun setItem(item: Any?) {
        if (item is MillExecutableChoice) {
            textField.text = item.editorText
        } else if (item is String) {
            textField.text = item
        }
    }

    override fun getItem(): Any = textField.text

    override fun selectAll() {
        textField.selectAll()
    }

    override fun addActionListener(listener: ActionListener?) {
        if (listener != null) {
            textField.addActionListener(listener)
        }
    }

    override fun removeActionListener(listener: ActionListener?) {
        if (listener != null) {
            textField.removeActionListener(listener)
        }
    }
}

private class ExecutableChoiceRenderer : ListCellRenderer<MillExecutableChoice> {
    private val nameLabel = JBLabel()
    private val pathLabel = JBLabel()
    private val panel = JBPanel<JBPanel<*>>(BorderLayout(JBUI.scale(8), 0)).apply {
        border = BorderFactory.createEmptyBorder(JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8))
        add(nameLabel, BorderLayout.WEST)
        add(pathLabel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out MillExecutableChoice>,
        value: MillExecutableChoice?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean,
    ): Component {
        val background = if (isSelected) list.selectionBackground else list.background
        val foreground = if (isSelected) list.selectionForeground else list.foreground
        panel.background = background
        nameLabel.foreground = foreground
        pathLabel.foreground = if (isSelected) foreground else JBColor.namedColor("Label.infoForeground", JBColor.GRAY)
        nameLabel.text = value?.displayName.orEmpty()
        pathLabel.text = value?.detailText.orEmpty()
        panel.toolTipText = value?.tooltipText
        return panel
    }
}
