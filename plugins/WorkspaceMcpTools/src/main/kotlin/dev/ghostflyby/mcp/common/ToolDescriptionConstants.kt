/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.common

internal const val VFS_URL_PARAM_DESCRIPTION =
    """Target VFS URL. returned from other mcp calls, or constructed by the caller. Some known URL formats for manual construction with no need for calling other mcp tools:
        |specifically for a local file, a standard file url like file:///absolute/path/to/file.txt
        |specifically for a jar or other zip filetree content, a jar url with `<local path>!<path in zip>` jar:///absolute/path/archive.zip!/path/in/archive. jar:// URLs can operate on ZIP/JAR internal trees.
        |example for Gradle cache sources: jar:///Users/<you>/.gradle/caches/.../idea-253.x-sources.jar!/com/intellij/navigation/ChooseByNameContributorEx.java
        |Most file discovery and reading should use MCP VFS/scope tools rather than shell commands."""
