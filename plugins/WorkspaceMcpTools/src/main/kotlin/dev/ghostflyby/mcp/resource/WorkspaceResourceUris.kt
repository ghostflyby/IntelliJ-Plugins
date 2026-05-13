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

package dev.ghostflyby.mcp.resource

/// Unified scheme prefix for all workspace resources.
/// Format: ij-workspace://{instanceKey}/projects/{projectKey}/{kind}/{rawTail}
internal const val WORKSPACE_URI_SCHEME = "ij-workspace://"

internal const val PROJECTS_SEGMENT = "/projects/"
internal const val KIND_FILES = "files"
internal const val KIND_DOCUMENTS = "documents"
internal const val KIND_VFS = "vfs"
internal const val KIND_DOCUMENT_VFS = "document-vfs"

internal enum class WorkspaceResourceKind {
    FILES,
    DOCUMENTS,
    VFS,
    DOCUMENT_VFS,
}

/// Decoded workspace resource URI.
internal data class WorkspaceResourceUri(
    val instanceKey: String,
    val projectKey: String,
    val kind: WorkspaceResourceKind,
    val tail: String,
)

// ---- builders ----

internal fun workspaceFileUri(
    instanceKey: String,
    projectKey: String,
    relativePath: String,
): String {
    validateProjectRelativePath(relativePath)
    return "${WORKSPACE_URI_SCHEME}${instanceKey}/projects/${projectKey}/${KIND_FILES}/${relativePath}"
}

internal fun validateProjectRelativePath(relativePath: String) {
    require(relativePath.isNotBlank()) { "relativePath must not be blank." }
    require(!relativePath.startsWith('/')) { "relativePath must not be absolute: $relativePath" }
    require(relativePath.split('/').none { it == ".." }) {
        "relativePath must not contain '..' segments: $relativePath"
    }
}

internal fun workspaceDocumentUri(
    instanceKey: String,
    projectKey: String,
    relativePath: String,
): String {
    validateProjectRelativePath(relativePath)
    return "${WORKSPACE_URI_SCHEME}${instanceKey}/projects/${projectKey}/${KIND_DOCUMENTS}/${relativePath}"
}

internal fun workspaceVfsUri(
    instanceKey: String,
    projectKey: String,
    rawIntellijVfsUrl: String,
): String {
    require(rawIntellijVfsUrl.isNotBlank()) { "rawVfsUrl must not be blank." }
    return "${WORKSPACE_URI_SCHEME}${instanceKey}/projects/${projectKey}/${KIND_VFS}/${rawIntellijVfsUrl}"
}

internal fun workspaceDocumentVfsUri(
    instanceKey: String,
    projectKey: String,
    rawIntellijVfsUrl: String,
): String {
    require(rawIntellijVfsUrl.isNotBlank()) { "rawVfsUrl must not be blank." }
    return "${WORKSPACE_URI_SCHEME}${instanceKey}/projects/${projectKey}/${KIND_DOCUMENT_VFS}/${rawIntellijVfsUrl}"
}

// ---- decoder ----

internal fun tryDecodeWorkspaceResourceUri(uri: String): WorkspaceResourceUri? {
    if (!uri.startsWith(WORKSPACE_URI_SCHEME)) return null
    val afterScheme = uri.removePrefix(WORKSPACE_URI_SCHEME)
    val projectsIdx = afterScheme.indexOf(PROJECTS_SEGMENT)
    if (projectsIdx < 0) return null
    val instanceKey = afterScheme.substring(0, projectsIdx)
    if (instanceKey.isBlank()) return null

    val afterProjects = afterScheme.substring(projectsIdx + PROJECTS_SEGMENT.length)
    val firstSlash = afterProjects.indexOf('/')
    if (firstSlash < 0) return null
    val projectKey = afterProjects.substring(0, firstSlash)
    if (projectKey.isBlank()) return null

    val afterProjectKey = afterProjects.substring(firstSlash + 1)
    val kindEnd = afterProjectKey.indexOf('/')
    if (kindEnd < 0) return null
    val kindStr = afterProjectKey.substring(0, kindEnd)
    val kind = when (kindStr) {
        KIND_FILES -> WorkspaceResourceKind.FILES
        KIND_DOCUMENTS -> WorkspaceResourceKind.DOCUMENTS
        KIND_VFS -> WorkspaceResourceKind.VFS
        KIND_DOCUMENT_VFS -> WorkspaceResourceKind.DOCUMENT_VFS
        else -> return null
    }
    val tail = afterProjectKey.substring(kindEnd + 1)
    if (tail.isBlank()) return null

    return WorkspaceResourceUri(
        instanceKey = instanceKey,
        projectKey = projectKey,
        kind = kind,
        tail = tail,
    )
}
