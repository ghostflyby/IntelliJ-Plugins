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

import com.intellij.openapi.observable.util.bind
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import dev.ghostflyby.intellij.ui.*
import dev.ghostflyby.mill.Bundle

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
            .bindItems(model.executableChoicesProperty)
            .bindSelectedItemKey(model.executableSelectedChoiceKeyBindingProperty)
            .bindEditorText(model.executableInputTextBindingProperty)
            .bindRightHint(model.executableVersionTextProperty)
            .bindRightHintError(model.executableStatusIsErrorProperty)
            .resizableColumn()
            .align(Align.FILL)
            .validationOnApply { component ->
                model.currentManualPathValidationMessage()?.let { ValidationInfo(it, component) }
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
