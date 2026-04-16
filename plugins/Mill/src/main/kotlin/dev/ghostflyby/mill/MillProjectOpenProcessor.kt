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
