/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.navigation

import dev.ghostflyby.mcp.navigation.tools.*
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
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

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerTool<NavigationSymbolInfoArgs>(
            name = "navigation_get_symbol_info",
            schema = NavigationSymbolInfoArgs::class.jsonSchema,
            description = "Retrieves symbol declaration and IDE quick documentation markdown for the source position (1-based row/column) in the specified VFS URL.",
            handler = { args, request -> navigationGetSymbolInfoHandler(args, request) },
        )
        registerTool<NavigationSymbolInfoByOffsetArgs>(
            name = "navigation_get_symbol_info_by_offset",
            schema = NavigationSymbolInfoByOffsetArgs::class.jsonSchema,
            description = "Retrieves symbol declaration and IDE quick documentation markdown by source offset (0-based) in the specified VFS URL.",
            handler = { args, request -> navigationGetSymbolInfoByOffsetHandler(args, request) },
        )
        registerTool<NavigationSymbolInfoAutoPositionArgs>(
            name = "navigation_get_symbol_info_auto_position",
            schema = NavigationSymbolInfoAutoPositionArgs::class.jsonSchema,
            description = "Retrieves symbol info by either row/column or offset. Exactly one positioning mode must be provided.",
            handler = { args, request -> navigationGetSymbolInfoAutoPositionHandler(args, request) },
        )
        registerTool<NavigationSymbolInfoArgs>(
            name = "navigation_get_symbol_info_quick",
            schema = NavigationSymbolInfoArgs::class.jsonSchema,
            description = "First-call friendly symbol info lookup by URI + row/column with normalized position in response.",
            handler = { args, request -> navigationGetSymbolInfoQuickHandler(args, request) },
        )
        registerTool<NavigationSymbolInfoBatchArgs>(
            name = "navigation_get_symbol_info_batch",
            schema = NavigationSymbolInfoBatchArgs::class.jsonSchema,
            description = "Batch retrieve symbol declaration and IDE quick documentation markdown for source positions.",
            handler = { args, request -> navigationGetSymbolInfoBatchHandler(args, request) },
        )
        registerTool<NavigationSourcePositionArgs>(
            name = "navigation_to_reference",
            schema = NavigationSourcePositionArgs::class.jsonSchema,
            description = "Resolve a reference at source position (1-based row/column) to its target declaration location.",
            handler = { args, request -> navigationToReferenceHandler(args, request) },
        )
        registerTool<NavigationSourcePositionArgs>(
            name = "navigation_to_type_definition",
            schema = NavigationSourcePositionArgs::class.jsonSchema,
            description = "Resolve best-effort type declaration for the symbol at source position (1-based row/column). May produce false negatives in some languages/PSI shapes.",
            handler = { args, request -> navigationToTypeDefinitionHandler(args, request) },
        )
        registerTool<NavigationSourcePositionArgs>(
            name = "navigation_to_implementation",
            schema = NavigationSourcePositionArgs::class.jsonSchema,
            description = "Resolve implementations for a reference at source position (1-based row/column).",
            handler = { args, request -> navigationToImplementationHandler(args, request) },
        )
        registerTool<NavigationSourcePositionArgs>(
            name = "navigation_find_overrides",
            schema = NavigationSourcePositionArgs::class.jsonSchema,
            description = "Find override/implementation declarations for the symbol at source position (1-based row/column).",
            handler = { args, request -> navigationFindOverridesHandler(args, request) },
        )
        registerTool<NavigationSourcePositionArgs>(
            name = "navigation_find_inheritors",
            schema = NavigationSourcePositionArgs::class.jsonSchema,
            description = "Find inheritor declarations for the type symbol at source position (1-based row/column).",
            handler = { args, request -> navigationFindInheritorsHandler(args, request) },
        )
        registerTool<NavigationFindReferencesArgs>(
            name = "navigation_find_references",
            schema = NavigationFindReferencesArgs::class.jsonSchema,
            description = "Find reference usages for the symbol at source position (1-based row/column).",
            handler = { args, request -> navigationFindReferencesHandler(args, request) },
        )
        registerTool<NavigationSourcePositionArgs>(
            name = "navigation_get_callers",
            schema = NavigationSourcePositionArgs::class.jsonSchema,
            description = "Find caller references for the symbol at source position (1-based row/column). Uses heuristic filtering to prefer call/invocation references.",
            handler = { args, request -> navigationGetCallersHandler(args, request) },
        )
        registerTool<NavigationToReferenceBatchArgs>(
            name = "navigation_to_reference_batch",
            schema = NavigationToReferenceBatchArgs::class.jsonSchema,
            description = "Batch resolve references for multiple source positions.",
            handler = { args, request -> navigationToReferenceBatchHandler(args, request) },
        )
        registerTool<NavigationFindReferencesBatchArgs>(
            name = "navigation_find_references_batch",
            schema = NavigationFindReferencesBatchArgs::class.jsonSchema,
            description = "Batch find references for multiple source positions.",
            handler = { args, request -> navigationFindReferencesBatchHandler(args, request) },
        )
        return buildRegistration()
    }
}
