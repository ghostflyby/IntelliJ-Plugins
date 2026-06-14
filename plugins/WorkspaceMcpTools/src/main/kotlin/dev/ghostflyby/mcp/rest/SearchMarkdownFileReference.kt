/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

internal data class SearchMarkdownFileReference(
    val path: String,
    val encodedFileUrl: String,
)

internal fun markdownFileReference(
    filePath: String,
    fileUrl: String,
    encodedFileUrl: String,
): SearchMarkdownFileReference {
    return if (filePath.isCompactRelativePath()) {
        SearchMarkdownFileReference(path = filePath, encodedFileUrl = "")
    } else {
        SearchMarkdownFileReference(path = fileUrl, encodedFileUrl = encodedFileUrl)
    }
}

private fun String.isCompactRelativePath(): Boolean {
    val normalized = replace('\\', '/')
    return normalized.isNotBlank() &&
        !normalized.startsWith("/") &&
        !normalized.contains("://") &&
        !normalized.contains("!/") &&
        !Regex("^[A-Za-z]:/").containsMatchIn(normalized)
}
