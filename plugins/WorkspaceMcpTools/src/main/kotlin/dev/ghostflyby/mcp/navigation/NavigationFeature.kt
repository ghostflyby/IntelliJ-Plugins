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

package dev.ghostflyby.mcp.navigation

import dev.ghostflyby.mcp.navigation.tools.NavigationFindReferencesArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationFindReferencesBatchArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationSourcePositionArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationSymbolInfoArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationSymbolInfoAutoPositionArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationSymbolInfoBatchArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationSymbolInfoByOffsetArgs
import dev.ghostflyby.mcp.navigation.tools.NavigationToReferenceBatchArgs
import dev.ghostflyby.mcp.navigation.tools.navigationFindInheritorsHandler
import dev.ghostflyby.mcp.navigation.tools.navigationFindOverridesHandler
import dev.ghostflyby.mcp.navigation.tools.navigationFindReferencesBatchHandler
import dev.ghostflyby.mcp.navigation.tools.navigationFindReferencesHandler
import dev.ghostflyby.mcp.navigation.tools.navigationGetCallersHandler
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoAutoPositionHandler
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoBatchHandler
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoByOffsetHandler
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoHandler
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoQuickHandler
import dev.ghostflyby.mcp.navigation.tools.navigationToImplementationHandler
import dev.ghostflyby.mcp.navigation.tools.navigationToReferenceBatchHandler
import dev.ghostflyby.mcp.navigation.tools.navigationToReferenceHandler
import dev.ghostflyby.mcp.navigation.tools.navigationToTypeDefinitionHandler
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

    override fun register(
        context: WorkspaceMcpFeatureRegistrationContext,
    ): WorkspaceMcpFeatureRegistration {
        context.registerTool<NavigationSymbolInfoArgs>("navigation_get_symbol_info",
            "Retrieves symbol declaration and IDE quick documentation markdown for the source position (1-based row/column) in the specified VFS URL.",
            handler = { args, sid -> navigationGetSymbolInfoHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSymbolInfoByOffsetArgs>("navigation_get_symbol_info_by_offset",
            "Retrieves symbol declaration and IDE quick documentation markdown by source offset (0-based) in the specified VFS URL.",
            handler = { args, sid -> navigationGetSymbolInfoByOffsetHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSymbolInfoAutoPositionArgs>("navigation_get_symbol_info_auto_position",
            "Retrieves symbol info by either row/column or offset. Exactly one positioning mode must be provided.",
            handler = { args, sid -> navigationGetSymbolInfoAutoPositionHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSymbolInfoArgs>("navigation_get_symbol_info_quick",
            "First-call friendly symbol info lookup by URI + row/column with normalized position in response.",
            handler = { args, sid -> navigationGetSymbolInfoQuickHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSymbolInfoBatchArgs>("navigation_get_symbol_info_batch",
            "Batch retrieve symbol declaration and IDE quick documentation markdown for source positions.",
            handler = { args, sid -> navigationGetSymbolInfoBatchHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSourcePositionArgs>("navigation_to_reference",
            "Resolve a reference at source position (1-based row/column) to its target declaration location.",
            handler = { args, sid -> navigationToReferenceHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSourcePositionArgs>("navigation_to_type_definition",
            "Resolve best-effort type declaration for the symbol at source position (1-based row/column). May produce false negatives in some languages/PSI shapes.",
            handler = { args, sid -> navigationToTypeDefinitionHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSourcePositionArgs>("navigation_to_implementation",
            "Resolve implementations for a reference at source position (1-based row/column).",
            handler = { args, sid -> navigationToImplementationHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSourcePositionArgs>("navigation_find_overrides",
            "Find override/implementation declarations for the symbol at source position (1-based row/column).",
            handler = { args, sid -> navigationFindOverridesHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSourcePositionArgs>("navigation_find_inheritors",
            "Find inheritor declarations for the type symbol at source position (1-based row/column).",
            handler = { args, sid -> navigationFindInheritorsHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationFindReferencesArgs>("navigation_find_references",
            "Find reference usages for the symbol at source position (1-based row/column).",
            handler = { args, sid -> navigationFindReferencesHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationSourcePositionArgs>("navigation_get_callers",
            "Find caller references for the symbol at source position (1-based row/column). Uses heuristic filtering to prefer call/invocation references.",
            handler = { args, sid -> navigationGetCallersHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationToReferenceBatchArgs>("navigation_to_reference_batch",
            "Batch resolve references for multiple source positions.",
            handler = { args, sid -> navigationToReferenceBatchHandler(args, sid, context.requestRunner) })
        context.registerTool<NavigationFindReferencesBatchArgs>("navigation_find_references_batch",
            "Batch find references for multiple source positions.",
            handler = { args, sid -> navigationFindReferencesBatchHandler(args, sid, context.requestRunner) })
        return context.buildRegistration()
    }
}
