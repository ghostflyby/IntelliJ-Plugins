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

internal const val VFS_RESOURCE_PREFIX = "ij-workspace-vfs://"
internal const val DOCUMENT_RESOURCE_PREFIX = "ij-workspace-document://"

internal enum class WorkspaceResourceKind {
    VFS,
    DOCUMENT,
}

internal data class WorkspaceResourceUri(
    val kind: WorkspaceResourceKind,
    val rawVfsUrl: String,
)

internal fun vfsResourceUri(rawVfsUrl: String): String {
    require(rawVfsUrl.isNotBlank()) { "rawVfsUrl must not be blank." }
    return VFS_RESOURCE_PREFIX + rawVfsUrl
}

internal fun documentResourceUri(rawVfsUrl: String): String {
    require(rawVfsUrl.isNotBlank()) { "rawVfsUrl must not be blank." }
    return DOCUMENT_RESOURCE_PREFIX + rawVfsUrl
}

internal fun rawVfsUrlFromVfsResourceUri(uri: String): String {
    return decodeWorkspaceResourceUri(uri, VFS_RESOURCE_PREFIX).rawVfsUrl
}

internal fun rawVfsUrlFromDocumentResourceUri(uri: String): String {
    return decodeWorkspaceResourceUri(uri, DOCUMENT_RESOURCE_PREFIX).rawVfsUrl
}

internal fun tryDecodeWorkspaceResourceUri(uri: String): WorkspaceResourceUri? {
    if (uri.startsWith(VFS_RESOURCE_PREFIX)) {
        val rawVfsUrl = uri.removePrefix(VFS_RESOURCE_PREFIX)
        if (rawVfsUrl.isNotBlank()) {
            return WorkspaceResourceUri(WorkspaceResourceKind.VFS, rawVfsUrl)
        }
        return null
    }
    if (uri.startsWith(DOCUMENT_RESOURCE_PREFIX)) {
        val rawVfsUrl = uri.removePrefix(DOCUMENT_RESOURCE_PREFIX)
        if (rawVfsUrl.isNotBlank()) {
            return WorkspaceResourceUri(WorkspaceResourceKind.DOCUMENT, rawVfsUrl)
        }
        return null
    }
    return null
}

private fun decodeWorkspaceResourceUri(uri: String, prefix: String): WorkspaceResourceUri {
    require(uri.startsWith(prefix)) { "Resource URI must start with '$prefix'." }
    val rawVfsUrl = uri.removePrefix(prefix)
    require(rawVfsUrl.isNotBlank()) { "Resource URI must contain a non-blank raw URL after '$prefix'." }
    val kind = when (prefix) {
        VFS_RESOURCE_PREFIX -> WorkspaceResourceKind.VFS
        DOCUMENT_RESOURCE_PREFIX -> WorkspaceResourceKind.DOCUMENT
        else -> error("Unknown workspace resource prefix: $prefix")
    }
    return WorkspaceResourceUri(kind, rawVfsUrl)
}
