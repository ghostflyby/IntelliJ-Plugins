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

import dev.ghostflyby.mcp.navigation.tools.navigationFindInheritorsSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationFindOverridesSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationFindReferencesBatchSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationFindReferencesSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationGetCallersSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoAutoPositionSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoBatchSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoByOffsetSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoQuickSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationGetSymbolInfoSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationToImplementationSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationToReferenceBatchSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationToReferenceSdkTool
import dev.ghostflyby.mcp.navigation.tools.navigationToTypeDefinitionSdkTool
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
        context.registerTool(navigationGetSymbolInfoSdkTool())
        context.registerTool(navigationGetSymbolInfoByOffsetSdkTool())
        context.registerTool(navigationGetSymbolInfoAutoPositionSdkTool())
        context.registerTool(navigationGetSymbolInfoQuickSdkTool())
        context.registerTool(navigationGetSymbolInfoBatchSdkTool())
        context.registerTool(navigationToReferenceSdkTool())
        context.registerTool(navigationToTypeDefinitionSdkTool())
        context.registerTool(navigationToImplementationSdkTool())
        context.registerTool(navigationFindOverridesSdkTool())
        context.registerTool(navigationFindInheritorsSdkTool())
        context.registerTool(navigationFindReferencesSdkTool())
        context.registerTool(navigationGetCallersSdkTool())
        context.registerTool(navigationToReferenceBatchSdkTool())
        context.registerTool(navigationFindReferencesBatchSdkTool())
        return context.buildRegistration()
    }
}
