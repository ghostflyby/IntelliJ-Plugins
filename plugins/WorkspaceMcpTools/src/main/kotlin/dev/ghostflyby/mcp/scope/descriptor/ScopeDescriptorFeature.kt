/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.descriptor

import dev.ghostflyby.mcp.scope.descriptor.tools.ScopeDescriptorTools
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
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
 * - scope_filter_files
 *
 * These tools were migrated from an annotation-based toolset to the SDK typed tool pattern.
 */
internal class ScopeDescriptorFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-descriptor"

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerToolClass<ScopeDescriptorTools>()
        return buildRegistration()
    }
}
