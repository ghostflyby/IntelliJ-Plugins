/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.navigation

import dev.ghostflyby.mcp.navigation.tools.NavigationTools
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
        registerToolClass<NavigationTools>()
        return buildRegistration()
    }
}
