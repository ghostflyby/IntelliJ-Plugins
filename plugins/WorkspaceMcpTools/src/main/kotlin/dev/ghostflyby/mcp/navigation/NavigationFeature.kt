/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.navigation

import dev.ghostflyby.mcp.navigation.tools.*
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides navigation SDK tools migrated from
 * [SymbolNavigationMcpTools].
 *
 * Registers 14 navigation SDK tools:
 * - navigation_get_symbol_info
 * - navigation_get_symbol_info_by_offset
 * - navigation_get_symbol_info_auto_position
 * - navigation_get_symbol_info_quick
 * - navigation_get_symbol_info_batch
 * - navigation_to_reference
 * - navigation_to_type_definition
 * - navigation_to_implementation
 * - navigation_find_overrides
 * - navigation_find_inheritors
 * - navigation_find_references
 * - navigation_get_callers
 * - navigation_to_reference_batch
 * - navigation_find_references_batch
 */
internal class NavigationFeature : WorkspaceMcpFeature {
    override val featureName: String = "navigation"

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<WorkspaceListableResource> {
        return emptyList()
    }

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerTool<NavigationSymbolInfoArgs>(
            "navigation_get_symbol_info",
            "Retrieves symbol declaration and IDE quick documentation markdown for the source position (1-based row/column) in the specified VFS URL.",
            handler = { args, sid -> navigationGetSymbolInfoHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSymbolInfoByOffsetArgs>(
            "navigation_get_symbol_info_by_offset",
            "Retrieves symbol declaration and IDE quick documentation markdown by source offset (0-based) in the specified VFS URL.",
            handler = { args, sid -> navigationGetSymbolInfoByOffsetHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSymbolInfoAutoPositionArgs>(
            "navigation_get_symbol_info_auto_position",
            "Retrieves symbol info by either row/column or offset. Exactly one positioning mode must be provided.",
            handler = { args, sid -> navigationGetSymbolInfoAutoPositionHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSymbolInfoArgs>(
            "navigation_get_symbol_info_quick",
            "First-call friendly symbol info lookup by URI + row/column with normalized position in response.",
            handler = { args, sid -> navigationGetSymbolInfoQuickHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSymbolInfoBatchArgs>(
            "navigation_get_symbol_info_batch",
            "Batch retrieve symbol declaration and IDE quick documentation markdown for source positions.",
            handler = { args, sid -> navigationGetSymbolInfoBatchHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSourcePositionArgs>(
            "navigation_to_reference",
            "Resolve a reference at source position (1-based row/column) to its target declaration location.",
            handler = { args, sid -> navigationToReferenceHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSourcePositionArgs>(
            "navigation_to_type_definition",
            "Resolve best-effort type declaration for the symbol at source position (1-based row/column). May produce false negatives in some languages/PSI shapes.",
            handler = { args, sid -> navigationToTypeDefinitionHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSourcePositionArgs>(
            "navigation_to_implementation",
            "Resolve implementations for a reference at source position (1-based row/column).",
            handler = { args, sid -> navigationToImplementationHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSourcePositionArgs>(
            "navigation_find_overrides",
            "Find override/implementation declarations for the symbol at source position (1-based row/column).",
            handler = { args, sid -> navigationFindOverridesHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSourcePositionArgs>(
            "navigation_find_inheritors",
            "Find inheritor declarations for the type symbol at source position (1-based row/column).",
            handler = { args, sid -> navigationFindInheritorsHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationFindReferencesArgs>(
            "navigation_find_references",
            "Find reference usages for the symbol at source position (1-based row/column).",
            handler = { args, sid -> navigationFindReferencesHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationSourcePositionArgs>(
            "navigation_get_callers",
            "Find caller references for the symbol at source position (1-based row/column). Uses heuristic filtering to prefer call/invocation references.",
            handler = { args, sid -> navigationGetCallersHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationToReferenceBatchArgs>(
            "navigation_to_reference_batch",
            "Batch resolve references for multiple source positions.",
            handler = { args, sid -> navigationToReferenceBatchHandler(args, sid, requestRunner) },
        )
        registerTool<NavigationFindReferencesBatchArgs>(
            "navigation_find_references_batch",
            "Batch find references for multiple source positions.",
            handler = { args, sid -> navigationFindReferencesBatchHandler(args, sid, requestRunner) },
        )
        return buildRegistration()
    }
}
