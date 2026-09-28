/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import java.nio.file.Path

internal fun relativizePathOrNull(projectBasePath: String?, filePath: String): String? {
    if (projectBasePath.isNullOrBlank()) return null
    return runCatching {
        Path.of(projectBasePath).relativize(Path.of(filePath)).toString().replace('\\', '/')
    }.getOrNull()
}

internal fun relativizePathOrOriginal(projectBasePath: String?, filePath: String): String {
    return relativizePathOrNull(projectBasePath, filePath) ?: filePath
}
