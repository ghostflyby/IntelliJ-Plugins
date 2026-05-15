/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.symbol

import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.scope.symbol.tools.scopeSymbolSearchSdkTools
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides scope symbol search SDK tools:
 * - scope_search_symbols
 * - scope_search_symbols_quick
 * - scope_search_symbols_with_stage_progress
 * - scope_search_symbols_healthcheck
 *
 * These tools were migrated from an annotation-based McpToolset to the SDK typed tool pattern.
 */
internal class ScopeSymbolSearchFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-symbol-search"

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<WorkspaceListableResource> {
        return emptyList()
    }

    override fun register(
        context: WorkspaceMcpFeatureRegistrationContext,
    ): WorkspaceMcpFeatureRegistration {
        scopeSymbolSearchSdkTools().forEach { tool ->
            context.registerTool(tool)
        }
        return context.buildRegistration()
    }
}
