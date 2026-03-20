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
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.ui.components.fields.ExtendableTextComponent
import dev.ghostflyby.intellij.ui.EditableHintedComboBox
import dev.ghostflyby.intellij.ui.createEditableHintedComboBox
import dev.ghostflyby.mill.Bundle

internal fun createMillExecutableSelectorField(
    project: Project,
    viewModel: MillConfigurableViewModel,
): EditableHintedComboBox<MillExecutableChoice> {
    val selector = createEditableHintedComboBox<MillExecutableChoice>(
    )
        .configureInputResolution(
            createInlineItem = ::createInlineManualChoice,
            findItemByInput = { _, text -> viewModel.findExecutableChoiceByInput(text) },
        )
    selector.addExtension(
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
