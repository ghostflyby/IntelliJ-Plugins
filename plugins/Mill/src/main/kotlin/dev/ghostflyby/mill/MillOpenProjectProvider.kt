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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mill

import com.intellij.openapi.externalSystem.importing.AbstractOpenProjectProvider
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.externalSystem.service.project.trusted.ExternalSystemTrustedProjectDialog
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import dev.ghostflyby.mill.settings.MillProjectSettings

// AbstractOpenProjectProvider is still experimental on 2025.3, but it is the
// platform entry point used by external-system project opening/linking flows.
internal class MillOpenProjectProvider : AbstractOpenProjectProvider() {
    override val systemId: ProjectSystemId = MillConstants.systemId

    override fun isProjectFile(file: VirtualFile): Boolean {
        return !file.isDirectory && MillProjectResolverSupport.isProjectFileName(file.name)
    }

    override suspend fun linkProject(projectFile: VirtualFile, project: Project) {
        LOG.debug("Link Mill project '$projectFile' to existing project ${project.name}")

        val projectPath = MillProjectResolverSupport.findProjectRoot(projectFile.path)
        if (!ExternalSystemTrustedProjectDialog.confirmLinkingUntrustedProjectAsync(project, systemId, projectPath)) {
            return
        }

        val settings = MillProjectSettings().apply {
            externalProjectPath = projectPath.toString()
        }
        ExternalSystemUtil.linkExternalProject(
            settings,
            ImportSpecBuilder(project, systemId)
                .withActivateToolWindowOnStart(true)
                .withCallback(MillImportRefreshCallback(project)),
        )
    }
}
