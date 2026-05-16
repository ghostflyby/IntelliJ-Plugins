/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.search

import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.scope.search.tools.*
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides scope file search SDK tools:
 * - scope_search_files
 * - scope_search_files_quick
 * - scope_find_files_by_name_keyword
 * - scope_find_files_by_path_keyword
 * - find_in_directory_using_glob
 * - scope_find_source_file_by_class_name
 *
 * These tools were migrated from an annotation-based toolset to the SDK typed tool pattern.
 */
internal class ScopeFileSearchFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-file-search"

    override suspend fun computeListableResources(
        context: WorkspaceMcpFeatureContext,
    ): List<WorkspaceListableResource> {
        return emptyList()
    }

    override fun register(
        context: WorkspaceMcpFeatureRegistrationContext,
    ): WorkspaceMcpFeatureRegistration {
        context.registerTool<ScopeFileSearchArgs>(
            "scope_search_files",
            "Search files by name/path text or glob within a scope descriptor. " +
                "Returns matching file URLs and search diagnostics. " +
                "When directoryUrl is provided, this tool traverses that VFS subtree directly " +
                "(including jar:// ZIP/JAR roots such as Gradle cache source archives). " +
                "Prefer this over shell commands in most cases.",
            handler = { args, sid -> scopeFileSearchHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeFileSearchQuickArgs>(
            "scope_search_files_quick",
            "First-call friendly file search shortcut with preset scope and low-parameter defaults." +
                " " +
                "First-call friendly shortcut for agents with no prior context; uses non-interactive defaults and stable parameters.",
            handler = { args, sid -> scopeFileSearchQuickHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeFindFilesByNameArgs>(
            "scope_find_files_by_name_keyword",
            "Shortcut: search files by filename keyword within a scope. " +
                "For GLOBAL scopes without directoryUrl, this uses indexed name lookup where possible.",
            handler = { args, sid -> scopeFindFilesByNameHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeFindFilesByPathArgs>(
            "scope_find_files_by_path_keyword",
            "Shortcut: search files by path keyword within a scope. " +
                "When directoryUrl points to jar:// roots, path keywords match archive-internal paths.",
            handler = { args, sid -> scopeFindFilesByPathHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeFindInDirectoryGlobArgs>(
            "find_in_directory_using_glob",
            "Shortcut: find files in a directory by glob pattern and scope. " +
                "Works with arbitrary VFS directories, including jar:// URLs in Gradle caches. " +
                "Example: directoryUrl='jar:///Users/<you>/.gradle/caches/.../idea-253.x-sources.jar!/', " +
                "globPattern='**/FindSymbolParameters.java'.",
            handler = { args, sid -> scopeFindInDirectoryGlobHandler(args, sid, context.requestRunner) },
        )
        context.registerTool<ScopeFindSourceFileByClassNameArgs>(
            "scope_find_source_file_by_class_name",
            "Find likely source files by class name across project and libraries, with source-preferred ranking." +
                " " +
                "MCP-first Policy: Any code/symbol/IDE API lookup should use MCP-related tools first; " +
                "shell fallback is only allowed when MCP tools have been exhausted and failure reasons recorded.",
            handler = { args, sid -> scopeFindSourceFileByClassNameHandler(args, sid, context.requestRunner) },
        )
        return context.buildRegistration()
    }
}
