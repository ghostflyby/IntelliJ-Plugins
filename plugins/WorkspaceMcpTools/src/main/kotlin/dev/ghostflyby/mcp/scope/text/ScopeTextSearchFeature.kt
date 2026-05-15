/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.text

import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.scope.text.tools.scopeTextSearchSdkTools
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides scope text search SDK tools:
 * - scope_search_text
 * - scope_search_text_quick
 * - scope_search_text_by_plain
 * - scope_search_text_by_regex
 * - scope_replace_text_preview
 * - scope_replace_text_apply
 *
 * These tools were migrated from an annotation-based toolset to the SDK typed tool pattern.
 */
internal class ScopeTextSearchFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-text-search"

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<WorkspaceListableResource> {
        return emptyList()
    }

    override fun register(
        context: WorkspaceMcpFeatureRegistrationContext,
    ): WorkspaceMcpFeatureRegistration {
        scopeTextSearchSdkTools().forEach { tool ->
            context.registerTool(tool)
        }
        return context.buildRegistration()
    }
}
