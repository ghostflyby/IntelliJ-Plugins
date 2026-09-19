/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.service.ui.DefaultExternalSystemUiAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.projectImport.ProjectOpenProcessor
import javax.swing.Icon

internal class MillProjectOpenProcessor : ProjectOpenProcessor() {
    private val importProvider = MillOpenProjectProvider()

    override val name: String
        get() = MillConstants.systemId.readableName

    override val icon: Icon
        get() = DefaultExternalSystemUiAware.INSTANCE.projectIcon

    override fun canOpenProject(file: VirtualFile): Boolean {
        return importProvider.canOpenProject(file)
    }

    override suspend fun openProjectAsync(
        virtualFile: VirtualFile,
        projectToClose: Project?,
        forceOpenInNewFrame: Boolean,
    ): Project? {
        return importProvider.openProject(virtualFile, projectToClose, forceOpenInNewFrame)
    }

    override fun canImportProjectAfterwards(): Boolean = true

    override suspend fun importProjectAfterwardsAsync(project: Project, file: VirtualFile) {
        importProvider.linkToExistingProjectAsync(file, project)
    }
}
