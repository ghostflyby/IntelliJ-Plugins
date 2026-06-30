/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.ui.GroupedComboBoxRenderer
import com.intellij.ui.components.fields.ExtendableTextComponent
import dev.ghostflyby.intellij.ui.EditableHintedComboBox
import dev.ghostflyby.intellij.ui.EditableHintedComboBoxAdapter
import dev.ghostflyby.mill.Bundle
import javax.swing.DefaultComboBoxModel

internal fun createMillExecutableSelectorField(
    project: Project,
    viewModel: MillConfigurableViewModel,
): EditableHintedComboBox<MillExecutableChoice> {
    val selector = EditableHintedComboBox(
        model = DefaultComboBoxModel(),
        adapter = MillExecutableChoiceComboBoxAdapter(viewModel),
    )
    selector.renderer = object : GroupedComboBoxRenderer<MillExecutableChoice>(selector) {
        override fun getText(item: MillExecutableChoice): String {
            return item.displayName
        }

        override fun separatorFor(value: MillExecutableChoice): ListSeparator? {
            return null
        }
    }
    selector.editorTextField.addExtension(
        ExtendableTextComponent.Extension.create(
            AllIcons.General.OpenDisk,
            AllIcons.General.OpenDiskHover,
            Bundle.message("settings.mill.executable.manual.title"),
        ) {
            FileChooser.chooseFile(
                FileChooserDescriptorFactory.singleFile()
                    .withTitle(Bundle.message("settings.mill.executable.manual.title")),
                project,
                selector,
                null,
            ) { virtualFile ->
                selector.selectedItem = createInlineManualChoice(virtualFile.path)
            }
        },
    )
    return selector
}

private class MillExecutableChoiceComboBoxAdapter(
    private val viewModel: MillConfigurableViewModel,
) : EditableHintedComboBoxAdapter<MillExecutableChoice> {
    override fun text(t: MillExecutableChoice): String {
        return t.editorText
    }

    override fun leftHint(t: MillExecutableChoice?): String {
        return t?.editorHintText.orEmpty()
    }

    override fun fromText(
        text: String,
    ): MillExecutableChoice? {
        val trimmedText = text.trim()
        return viewModel.findExecutableChoiceByInput(trimmedText)
            ?: trimmedText.takeUnless(String::isBlank)?.let(::createInlineManualChoice)
    }
}

private fun createInlineManualChoice(text: String): MillExecutableChoice {
    return MillExecutableChoice(
        key = "manual:$text",
        displayName = text,
        editorHintText = null,
        source = MillExecutableSource.MANUAL,
        manualPath = text,
        tooltipText = text,
    )
}
