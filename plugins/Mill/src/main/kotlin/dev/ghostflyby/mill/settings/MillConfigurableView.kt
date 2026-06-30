/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.settings

import com.intellij.openapi.observable.util.bind
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.*
import dev.ghostflyby.intellij.ui.EditableHintedComboBox
import dev.ghostflyby.intellij.ui.bindRightHint
import dev.ghostflyby.mill.Bundle
import javax.swing.DefaultComboBoxModel

internal fun millConfigurableView(
    project: Project,
    model: MillConfigurableViewModel,
) = panel {
    if (!model.hasLinkedProjects) {
        row {
            label(Bundle.message("settings.mill.no.linked.projects"))
        }.rowComment(Bundle.message("settings.mill.no.linked.projects.comment"))
        return@panel
    }

    if (model.hasMultipleLinkedProjects) {
        row(Bundle.message("settings.mill.project.label")) {
            comboBox(
                items = model.linkedProjectPaths,
                renderer = SimpleListCellRenderer.create("") { value ->
                    value?.let(model::presentableProjectName).orEmpty()
                },
            )
                .bindItem(model.selectedProjectPathProperty)
        }
    } else {
        row(Bundle.message("settings.mill.project.label")) {
            label("")
                .align(Align.FILL)
                .applyToComponent { bind(model.selectedProjectDisplayNameProperty) }
        }
    }

    row(Bundle.message("settings.mill.executable.label")) {
        cell(createMillExecutableSelectorField(project, model))
            .installExecutableChoicesModel(model)
            .bindItem(model.executableSelectedChoiceProperty::get, model.executableSelectedChoiceProperty::set)
            .bindRightHint(model.executableVersionTextProperty)
            .resizableColumn()
            .align(Align.FILL)
            .validationOnApply { component ->
                model.currentExecutableValidationMessage()?.let { ValidationInfo(it, component) }
            }
    }.contextHelp(Bundle.message("settings.mill.executable.section.tooltip"))

    row {
        checkBox(Bundle.message("settings.mill.metadata.import"))
            .bindSelected(model.useMillMetadataDuringImportProperty)
    }.contextHelp(Bundle.message("settings.mill.metadata.import.tooltip"))

    row {
        checkBox(Bundle.message("settings.mill.per.module.tasks"))
            .bindSelected(model.createPerModuleTaskNodesProperty)
    }.contextHelp(Bundle.message("settings.mill.per.module.tasks.tooltip"))
}

private fun Cell<EditableHintedComboBox<MillExecutableChoice>>.installExecutableChoicesModel(
    model: MillConfigurableViewModel,
): Cell<EditableHintedComboBox<MillExecutableChoice>> = applyToComponent {
    fun replaceExecutableChoices(choices: List<MillExecutableChoice>) {
        val mutableModel = this.model as? DefaultComboBoxModel<MillExecutableChoice> ?: return
        mutableModel.removeAllElements()
        choices.forEach(mutableModel::addElement)
    }

    replaceExecutableChoices(model.executableChoicesProperty.get())
    model.executableChoicesProperty.afterChange(::replaceExecutableChoices)
}
