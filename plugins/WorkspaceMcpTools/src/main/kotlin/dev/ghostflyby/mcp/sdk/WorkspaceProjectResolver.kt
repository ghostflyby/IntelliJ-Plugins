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

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.exists

internal class WorkspaceProjectResolver(
    private val projectManager: ProjectManager = ProjectManager.getInstance(),
) {
    private val vfsManager: VirtualFileManager
        get() = service<VirtualFileManager>()

    internal fun openProjects(): List<Project> {
        return projectManager.openProjects
            .filterNot { it.isDisposed }
            .sortedBy { it.basePath ?: it.name }
    }

    internal suspend fun resolve(
        explicitProjectPath: String? = null,
        rawVfsUrl: String? = null,
    ): WorkspaceProjectResolution {
        explicitProjectPath?.takeIf { it.isNotBlank() }?.let { projectPath ->
            findByProjectPath(projectPath)?.let { project ->
                return WorkspaceProjectResolution.Resolved(project, WorkspaceProjectResolutionReason.EXPLICIT_PROJECT_PATH)
            }
        }

        rawVfsUrl?.takeIf { it.isNotBlank() }?.let { url ->
            findByRawVfsUrl(url)?.let { project ->
                return WorkspaceProjectResolution.Resolved(project, WorkspaceProjectResolutionReason.RAW_VFS_URL)
            }
        }

        val projects = openProjects()
        return when (projects.size) {
            0 -> WorkspaceProjectResolution.Unresolved("No open IntelliJ projects are available.")
            1 -> WorkspaceProjectResolution.Resolved(projects.single(), WorkspaceProjectResolutionReason.SINGLE_OPEN_PROJECT)
            else -> WorkspaceProjectResolution.Unresolved(
                "Multiple IntelliJ projects are open. Provide an explicit project path or a resource URI inside one project.",
            )
        }
    }

    private fun findByProjectPath(projectPath: String): Project? {
        val normalized = normalizePath(projectPath)
        return openProjects().firstOrNull { project ->
            project.basePath?.let { normalizePath(it) } == normalized
        }
    }

    private suspend fun findByRawVfsUrl(rawVfsUrl: String): Project? {
        val file = vfsManager.findFileByUrl(rawVfsUrl) ?: return null
        return readAction {
            openProjects()
                .filter { project -> project.owns(file) }
                .maxByOrNull { project -> project.basePath?.length ?: 0 }
        }
    }

    private fun Project.owns(file: VirtualFile): Boolean {
        return ProjectFileIndex.getInstance(this).isInContent(file) ||
            basePath?.let { file.path.startsWith(it) } == true
    }

    private fun normalizePath(path: String): String {
        return runCatching {
            val nioPath = Path.of(path)
            if (nioPath.exists()) {
                nioPath.toRealPath().absolutePathString()
            } else {
                nioPath.toAbsolutePath().normalize().absolutePathString()
            }
        }.getOrElse {
            path.trim().trimEnd('/')
        }
    }
}

internal sealed class WorkspaceProjectResolution {
    data class Resolved(
        val project: Project,
        val reason: WorkspaceProjectResolutionReason,
    ) : WorkspaceProjectResolution()

    data class Unresolved(
        val message: String,
    ) : WorkspaceProjectResolution()
}

internal enum class WorkspaceProjectResolutionReason {
    EXPLICIT_PROJECT_PATH,
    RAW_VFS_URL,
    SINGLE_OPEN_PROJECT,
}
