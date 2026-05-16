/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.descriptor

import dev.ghostflyby.mcp.scope.descriptor.tools.*
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
        registerTool<ScopeListCatalogArgs>(
            "scope_list_catalog",
            "List available search scopes (Find-like catalog) with stable scopeRefId and metadata.",
            handler = { args, request -> scopeListCatalogHandler(args, request) },
        )
        registerTool<ScopeGetDefaultDescriptorArgs>(
            "scope_get_default_descriptor",
            "Return a ready-to-use default scope descriptor by preset, avoiding catalog+program assembly on first call.",
            handler = { args, request -> scopeGetDefaultDescriptorHandler(args, request) },
        )
        registerTool<ScopeResolveStandardDescriptorArgs>(
            "scope_resolve_standard_descriptor",
            "Resolve a standard IDE scope id directly to a normalized reusable descriptor.",
            handler = { args, request -> scopeResolveStandardDescriptorHandler(args, request) },
        )
        registerTool<ScopeCatalogFindByIntentArgs>(
            "scope_catalog_find_by_intent",
            "Find a compact scope catalog subset by intent " +
                "(project-only, with-libraries, changed/open/current file) " +
                "to reduce first-call catalog payload.",
            handler = { args, request -> scopeCatalogFindByIntentHandler(args, request) },
        )
        registerTool<ScopeValidatePatternArgs>(
            "scope_validate_pattern",
            "Validate a PackageSet pattern text used by IntelliJ scopes.",
            handler = { args, request -> scopeValidatePatternHandler(args, request) },
        )
        registerTool<ScopeResolveProgramArgs>(
            "scope_resolve_program",
            "Compile and normalize scope atoms and RPN tokens into a reusable scope descriptor.",
            handler = { args, request -> scopeResolveProgramHandler(args, request) },
        )
        registerTool<ScopeNormalizeDescriptorArgs>(
            "scope_normalize_program_descriptor",
            "Normalize and recompile an existing scope descriptor, " +
                "useful for migration and compatibility upgrades.",
            handler = { args, request -> scopeNormalizeDescriptorHandler(args, request) },
        )
        registerTool<ScopeContainsFileArgs>(
            "scope_contains_file",
            "Check whether a file URL belongs to a resolved scope descriptor.",
            handler = { args, request -> scopeContainsFileHandler(args, request) },
        )
        registerTool<ScopeFilterFilesArgs>(
            "scope_filter_files",
            "Filter file URLs by whether they belong to a resolved scope descriptor.",
            handler = { args, request -> scopeFilterFilesHandler(args, request) },
        )

        return buildRegistration()
    }
}
