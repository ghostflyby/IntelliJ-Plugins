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

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.service.settings.AbstractExternalProjectSettingsControl
import com.intellij.openapi.externalSystem.util.ExternalSystemUiUtil
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.GridBag
import com.intellij.util.execution.ParametersListUtil
import java.awt.GridBagConstraints

internal class MillProjectSettingsControl(
    settings: MillProjectSettings,
) : AbstractExternalProjectSettingsControl<MillProjectSettings>(settings) {
    private var executableLabel: JBLabel? = null
    private var executablePathField: TextFieldWithBrowseButton? = null
    private var executableComment: JBLabel? = null
    private var jvmOptionsLabel: JBLabel? = null
    private var jvmOptionsField: JBTextField? = null
    private var jvmOptionsComment: JBLabel? = null
    private var metadataImportCheckBox: JBCheckBox? = null
    private var perModuleTasksCheckBox: JBCheckBox? = null

    override fun fillExtraControls(
        content: com.intellij.openapi.externalSystem.util.PaintAwarePanel,
        indentLevel: Int,
    ) {
        val label = JBLabel("Mill executable")
        val pathField = TextFieldWithBrowseButton().apply {
            addBrowseFolderListener(TextBrowseFolderListener(FileChooserDescriptorFactory.singleFile(), project))
        }
        val comment = JBLabel("Leave the default value to use `mill` from PATH.")

        executableLabel = label
        executablePathField = pathField
        executableComment = comment

        content.add(label, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))

        val fieldConstraints = ExternalSystemUiUtil.getFillLineConstraints(indentLevel + 1)
            .fillCellHorizontally()
            .weightx(1.0)
            .anchor(GridBagConstraints.WEST)
        content.add(pathField, fieldConstraints)

        val commentConstraints: GridBag = ExternalSystemUiUtil.getCommentConstraints(indentLevel + 1)
        content.add(comment, commentConstraints)

        val jvmLabel = JBLabel("Mill JVM options")
        val jvmField = JBTextField()
        val jvmComment = JBLabel("Pass launcher JVM options such as `-J-Xmx2g -J-Dkey=value`.")
        val metadataCheckBox = JBCheckBox("Use Mill metadata during import")
        val perModuleTasksBox = JBCheckBox("Create per-module task nodes")

        jvmOptionsLabel = jvmLabel
        jvmOptionsField = jvmField
        jvmOptionsComment = jvmComment
        metadataImportCheckBox = metadataCheckBox
        perModuleTasksCheckBox = perModuleTasksBox

        content.add(jvmLabel, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))
        content.add(
            jvmField,
            ExternalSystemUiUtil.getFillLineConstraints(indentLevel + 1)
                .fillCellHorizontally()
                .weightx(1.0)
                .anchor(GridBagConstraints.WEST),
        )
        content.add(jvmComment, ExternalSystemUiUtil.getCommentConstraints(indentLevel + 1))
        content.add(metadataCheckBox, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))
        content.add(perModuleTasksBox, ExternalSystemUiUtil.getFillLineConstraints(indentLevel))
    }

    override fun isExtraSettingModified(): Boolean {
        return currentExecutablePath() != initialSettings.millExecutablePath ||
            currentJvmOptions() != initialSettings.millJvmOptions ||
            isMetadataImportEnabled() != initialSettings.useMillMetadataDuringImport ||
            isPerModuleTaskNodesEnabled() != initialSettings.createPerModuleTaskNodes
    }

    override fun resetExtraSettings(isDefaultModuleCreation: Boolean) {
        executablePathField?.text = initialSettings.millExecutablePath
        jvmOptionsField?.text = initialSettings.millJvmOptions
        metadataImportCheckBox?.isSelected = initialSettings.useMillMetadataDuringImport
        perModuleTasksCheckBox?.isSelected = initialSettings.createPerModuleTaskNodes
    }

    override fun applyExtraSettings(settings: MillProjectSettings) {
        settings.millExecutablePath = currentExecutablePath()
        settings.millJvmOptions = currentJvmOptions()
        settings.useMillMetadataDuringImport = isMetadataImportEnabled()
        settings.createPerModuleTaskNodes = isPerModuleTaskNodesEnabled()
    }

    override fun validate(settings: MillProjectSettings): Boolean {
        val path = executablePathField?.text.orEmpty().trim()
        if (path.contains('\n')) {
            throw ConfigurationException("Mill executable path must be a single path.")
        }
        try {
            ParametersListUtil.parse(currentJvmOptions(), false, true)
        } catch (error: RuntimeException) {
            throw ConfigurationException("Mill JVM options could not be parsed: ${error.message.orEmpty()}")
        }
        return true
    }

    override fun updateInitialExtraSettings() {
        initialSettings.millExecutablePath = currentExecutablePath()
        initialSettings.millJvmOptions = currentJvmOptions()
        initialSettings.useMillMetadataDuringImport = isMetadataImportEnabled()
        initialSettings.createPerModuleTaskNodes = isPerModuleTaskNodesEnabled()
    }

    private fun currentExecutablePath(): String {
        val rawValue = executablePathField?.text.orEmpty().trim()
        return StringUtil.defaultIfEmpty(rawValue, MillConstants.defaultExecutable)
    }

    private fun currentJvmOptions(): String = jvmOptionsField?.text.orEmpty().trim()

    private fun isMetadataImportEnabled(): Boolean = metadataImportCheckBox?.isSelected ?: true

    private fun isPerModuleTaskNodesEnabled(): Boolean = perModuleTasksCheckBox?.isSelected ?: true
}
