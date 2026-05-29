/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mcp.server.WorkspaceProjectResolver
import dev.ghostflyby.mcp.server.workspaceInstanceKey
import dev.ghostflyby.mcp.server.workspaceProjectKey

internal fun workspaceRawVfsUri(instanceKey: String, rawVfsUrl: String): String {
    require(rawVfsUrl.isNotBlank()) { "rawVfsUrl must not be blank." }
    return "ij-workspace://$instanceKey/vfs/$rawVfsUrl"
}

internal fun workspaceProjectFileUri(
    instanceKey: String,
    projectKey: String,
    relativePath: String,
): String {
    validateProjectRelativePath(relativePath)
    return "ij-workspace://$instanceKey/projects/$projectKey/files/$relativePath"
}

internal fun fileContentResourceUrisForFile(file: VirtualFile): Set<String> {
    val projectResolver = service<WorkspaceProjectResolver>()
    val instanceKey = workspaceInstanceKey()
    return buildSet {
        add(workspaceRawVfsUri(instanceKey, file.url))
        projectResolver.openProjects().forEach { project ->
            projectRelativeUri(instanceKey, project, file)?.let(::add)
        }
    }
}

private fun projectRelativeUri(instanceKey: String, project: Project, file: VirtualFile): String? {
    val basePath = project.basePath ?: return null
    val relativePath = relativePathUnder(file.path, basePath) ?: return null
    return workspaceProjectFileUri(
        instanceKey = instanceKey,
        projectKey = workspaceProjectKey(project),
        relativePath = relativePath,
    )
}

private fun relativePathUnder(path: String, basePath: String): String? {
    val normalizedBase = basePath.trimEnd('/')
    return when {
        path == normalizedBase -> null
        path.startsWith("$normalizedBase/") -> path.removePrefix("$normalizedBase/")
        else -> null
    }
}
