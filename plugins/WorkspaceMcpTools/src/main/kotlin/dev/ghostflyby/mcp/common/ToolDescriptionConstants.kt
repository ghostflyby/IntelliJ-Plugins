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

internal const val SCOPE_QUICK_PRESET_PARAM_DESCRIPTION =
    "Preset scope to use. One of: PROJECT_FILES, ALL_PLACES, OPEN_FILES, PROJECT_AND_LIBRARIES, " +
        "PROJECT_PRODUCTION_FILES, PROJECT_TEST_FILES."

internal const val SCOPE_SYMBOL_QUICK_PRESET_PARAM_DESCRIPTION =
    "Preset scope for quick symbol search. One of: PROJECT_FILES, ALL_PLACES."

internal const val SCOPE_CATALOG_INTENT_PARAM_DESCRIPTION =
    "Selection intent for reducing catalog candidates. One of: PROJECT_ONLY, WITH_LIBRARIES, " +
        "CHANGED_FILES, OPEN_FILES, CURRENT_FILE."

internal const val SCOPE_TEXT_QUERY_MODE_PARAM_DESCRIPTION =
    "Search mode. One of: PLAIN, REGEX."

internal const val SCOPE_TEXT_SEARCH_CONTEXT_PARAM_DESCRIPTION =
    "Search context filter. One of: ANY, IN_STRING_LITERALS, IN_COMMENTS, EXCEPT_STRING_LITERALS, " +
        "EXCEPT_COMMENTS, EXCEPT_COMMENTS_AND_STRING_LITERALS."

internal const val QUALITY_SEVERITY_THRESHOLD_PARAM_DESCRIPTION =
    "Minimum severity threshold for returned problems. One of: ERROR, WARNING, WEAK_WARNING, INFO."

internal const val VFS_READ_MODE_PARAM_DESCRIPTION =
    "Read strategy. One of: FULL, CHAR_RANGE, LINE_RANGE."

internal const val SCOPE_ATOM_KIND_PARAM_DESCRIPTION =
    "Scope atom kind. One of: STANDARD, MODULE, NAMED_SCOPE, PATTERN, DIRECTORY, FILES, PROVIDER_SCOPE."

internal const val MODULE_SCOPE_FLAVOR_PARAM_DESCRIPTION =
    "Module scope flavor. One of: MODULE, MODULE_WITH_DEPENDENCIES, MODULE_WITH_LIBRARIES, " +
        "MODULE_WITH_DEPENDENCIES_AND_LIBRARIES."

internal const val SCOPE_ATOM_FAILURE_MODE_PARAM_DESCRIPTION =
    "Failure handling mode for atom resolution. One of: FAIL, EMPTY_SCOPE, SKIP."

internal const val SCOPE_PROGRAM_OP_PARAM_DESCRIPTION =
    "Program token operation in RPN order. One of: PUSH_ATOM, AND, OR, NOT."

internal const val MCP_FIRST_LIBRARY_QUERY_POLICY_DESCRIPTION_SUFFIX =
    " ## MCP-first Policy\n" +
        "- Any code/symbol/IDE API lookup should use `functions.mcp__idea__*` tools first.\n" +
        "- Do not directly parse IDE jars with shell commands such as `javap`, `jar tf`, or `grep`.\n" +
        "- Shell fallback is allowed only when ALL conditions are met:\n" +
        "  - already tried `scope_find_source_file_by_class_name`, `navigation_get_symbol_info`, and `vfs_read_api_signature`;\n" +
        "  - failure reasons are explicitly recorded (for example: class-only artifact, no sources, symbol index miss);\n" +
        "  - announce the planned fallback in commentary before running shell parsing."
