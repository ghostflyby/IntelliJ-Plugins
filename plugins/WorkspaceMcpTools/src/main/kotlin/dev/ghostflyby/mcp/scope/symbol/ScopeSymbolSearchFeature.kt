/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.symbol

import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.scope.symbol.tools.*
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
        context.registerTool<ScopeSymbolSearchArgs>(
            "scope_search_symbols",
            "Search symbols within a resolved scope descriptor. " +
                "Prefer IntelliJ index/contributor search path (Goto Symbol model) and apply post-filtering for LOCAL/MIXED semantics.",
            handler = { args, sid -> scopeSymbolSearchHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeSymbolSearchQuickArgs>(
            "scope_search_symbols_quick",
            "First-call friendly symbol search shortcut with low-parameter defaults and a preset scope." +
                " " +
                "First-call friendly shortcut for agents with no prior context; uses non-interactive defaults and stable parameters.",
            handler = { args, sid -> scopeSymbolSearchQuickHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeSymbolSearchWithStageProgressArgs>(
            "scope_search_symbols_with_stage_progress",
            "Search symbols and return stage counters for agent-side retry/expand decisions.",
            handler = { args, sid -> scopeSymbolSearchWithStageProgressHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeSymbolSearchHealthcheckArgs>(
            "scope_search_symbols_healthcheck",
            "Quickly check symbol search readiness (index state + provider mode) before a full search.",
            handler = { args, sid -> scopeSymbolSearchHealthcheckHandler(args, sid, context.requestRunner) },
        )
        return context.buildRegistration()
    }
}
