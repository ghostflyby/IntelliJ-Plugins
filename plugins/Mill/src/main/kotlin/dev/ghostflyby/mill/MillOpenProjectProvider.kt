/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")

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
