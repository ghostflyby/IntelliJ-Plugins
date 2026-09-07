/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")

package dev.ghostflyby.mill

import com.intellij.openapi.Disposable
import com.intellij.openapi.externalSystem.autolink.ExternalSystemProjectLinkListener
import com.intellij.openapi.externalSystem.autolink.ExternalSystemUnlinkedProjectAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import dev.ghostflyby.mill.settings.MillLocalSettings
import dev.ghostflyby.mill.settings.MillProjectSettings
import dev.ghostflyby.mill.settings.MillSettings
import dev.ghostflyby.mill.settings.MillSettingsListener
import java.nio.file.Path

// ExternalSystemProjectLinkListener is still experimental on 2025.3, but this
// extension is the public way to surface external-system import guidance after
// a project is opened.
//
// sync bridge for linkAndLoadProject(Project, String), which Marketplace counts
// as a deprecated API usage in the generated classfile.
internal class MillUnlinkedProjectAware : ExternalSystemUnlinkedProjectAware {
    override val systemId = MillConstants.systemId

    override fun buildFileExtensions(): Array<String> = arrayOf("sc", "mill", "yaml")

    override fun isBuildFile(project: Project, buildFile: VirtualFile): Boolean {
        return !buildFile.isDirectory && MillProjectResolverSupport.isProjectFileName(buildFile.name)
    }

    override fun isLinkedProject(project: Project, externalProjectPath: String): Boolean {
        return MillSettings.getInstance(project).getLinkedProjectSettings(externalProjectPath) != null
    }

    override fun subscribe(
        project: Project,
        listener: ExternalSystemProjectLinkListener,
        parentDisposable: Disposable,
    ) {
        MillSettings.getInstance(project).subscribe(
            object : MillSettingsListener {
                override fun onProjectsLinked(settings: Collection<MillProjectSettings>) {
                    val linkedProjectPaths = LinkedHashSet<String>()
                    settings.mapNotNullTo(linkedProjectPaths) { it.externalProjectPath }
                    linkedProjectPaths.forEach(listener::onProjectLinked)
                }

                override fun onProjectsUnlinked(linkedProjectPaths: Set<String>) {
                    linkedProjectPaths.forEach(listener::onProjectUnlinked)
                }
            },
            parentDisposable,
        )
    }

    override suspend fun linkAndLoadProjectAsync(project: Project, externalProjectPath: String) {
        val projectFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(Path.of(externalProjectPath))
            ?: return
        MillOpenProjectProvider().linkToExistingProjectAsync(projectFile, project)
    }

    override suspend fun unlinkProject(project: Project, externalProjectPath: String) {
        val linkedProjectPaths = setOf(externalProjectPath)
        MillLocalSettings.getInstance(project).forgetExternalProjects(linkedProjectPaths)
        MillSettings.getInstance(project).unlinkExternalProject(externalProjectPath)
    }
}
