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

package dev.ghostflyby.mcp.common

internal const val VFS_URL_PARAM_DESCRIPTION =
    """Target VFS URL. returned from other mcp calls, or constructed by the caller. Some known URL formats for manual construction with no need for calling other mcp tools:
        |specifically for a local file, a standard file url like file:///absolute/path/to/file.txt
        |specifically for a jar or other zip filetree content, a jar url with `<local path>!<path in zip>` jar:///absolute/path/archive.zip!/path/in/archive. jar:// URLs can operate on ZIP/JAR internal trees.
        |example for Gradle cache sources: jar:///Users/<you>/.gradle/caches/.../idea-253.x-sources.jar!/com/intellij/navigation/ChooseByNameContributorEx.java
        |Most file discovery and reading should use MCP VFS/scope tools rather than shell commands."""

internal const val AGENT_FIRST_CALL_SHORTCUT_DESCRIPTION_SUFFIX =
    " First-call friendly shortcut for agents with no prior context; uses non-interactive defaults and stable parameters."

internal const val ALLOW_UI_INTERACTIVE_SCOPES_PARAM_DESCRIPTION =
    "Whether UI-interactive scopes are allowed during descriptor resolution."
