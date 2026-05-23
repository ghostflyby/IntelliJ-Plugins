/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.search

import dev.ghostflyby.mcp.scope.search.tools.ScopeFileSearchTools
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides scope file search SDK tools:
 * - scope_search_files
 * - scope_search_files_quick
 * - scope_find_files_by_name_keyword
 * - scope_find_files_by_path_keyword
 * - find_in_directory_using_glob
 * - scope_find_source_file_by_class_name
 *
 * These tools were migrated from an annotation-based toolset to the SDK typed tool pattern.
 */
internal class ScopeFileSearchFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-file-search"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        registerToolClass<ScopeFileSearchTools>()

    }
}
