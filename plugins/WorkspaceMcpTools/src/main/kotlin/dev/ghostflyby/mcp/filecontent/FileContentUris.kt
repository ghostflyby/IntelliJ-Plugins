/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

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
