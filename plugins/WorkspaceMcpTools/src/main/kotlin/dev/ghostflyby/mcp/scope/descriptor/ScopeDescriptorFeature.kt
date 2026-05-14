/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.descriptor

import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeListCatalogSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeGetDefaultDescriptorSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeResolveStandardDescriptorSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeCatalogFindByIntentSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeValidatePatternSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeResolveProgramSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeNormalizeProgramDescriptorSdkTool
import dev.ghostflyby.mcp.scope.descriptor.tools.scopeContainsFileSdkTool
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides scope descriptor and catalog SDK tools:
 * - scope_list_catalog
 * - scope_get_default_descriptor
 * - scope_resolve_standard_descriptor
 * - scope_catalog_find_by_intent
 * - scope_validate_pattern
 * - scope_resolve_program
 * - scope_normalize_program_descriptor
 * - scope_contains_file
 *
 * These tools are migrated from [ScopeDescriptorMcpTools] (annotation-based toolset)
 * to the SDK typed tool pattern. The old annotation-based tools remain in place
 * during incremental migration.
 */
internal class ScopeDescriptorFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-descriptor"

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<WorkspaceListableResource> {
        // Tools-only feature; no listable resources.
        return emptyList()
    }

    override fun register(
        context: WorkspaceMcpFeatureRegistrationContext,
    ): WorkspaceMcpFeatureRegistration {
        context.registerTool(scopeListCatalogSdkTool())
        context.registerTool(scopeGetDefaultDescriptorSdkTool())
        context.registerTool(scopeResolveStandardDescriptorSdkTool())
        context.registerTool(scopeCatalogFindByIntentSdkTool())
        context.registerTool(scopeValidatePatternSdkTool())
        context.registerTool(scopeResolveProgramSdkTool())
        context.registerTool(scopeNormalizeProgramDescriptorSdkTool())
        context.registerTool(scopeContainsFileSdkTool())

        return context.buildRegistration()
    }
}
