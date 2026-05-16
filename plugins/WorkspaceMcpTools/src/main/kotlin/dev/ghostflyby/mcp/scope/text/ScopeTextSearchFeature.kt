/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.text

import dev.ghostflyby.mcp.scope.text.tools.*
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Workspace MCP feature that provides scope text search SDK tools:
 * - scope_search_text
 * - scope_search_text_quick
 * - scope_search_text_by_plain
 * - scope_search_text_by_regex
 * - scope_replace_text_preview
 * - scope_replace_text_apply
 *
 * These tools were migrated from an annotation-based toolset to the SDK typed tool pattern.
 */
internal class ScopeTextSearchFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-text-search"

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerTool<ScopeSearchTextArgs>(
            "scope_search_text",
            "Search text within a resolved scope descriptor using IntelliJ Find engine. " +
                "Supports plain text and regex mode, file mask, and search context.",
            handler = { args, request -> scopeSearchTextHandler(args, request) },
        )
        registerTool<ScopeSearchTextQuickArgs>(
            "scope_search_text_quick",
            "First-call friendly text search shortcut with preset scope." +
                " " +
                "First-call friendly shortcut for agents with no prior context; uses non-interactive defaults and stable parameters.",
            handler = { args, request -> scopeSearchTextQuickHandler(args, request) },
        )
        registerTool<ScopeSearchTextByPlainArgs>(
            "scope_search_text_by_plain",
            "Shortcut: search plain text within a resolved scope descriptor.",
            handler = { args, request -> scopeSearchTextByPlainHandler(args, request) },
        )
        registerTool<ScopeSearchTextByRegexArgs>(
            "scope_search_text_by_regex",
            "Shortcut: search regex pattern within a resolved scope descriptor.",
            handler = { args, request -> scopeSearchTextByRegexHandler(args, request) },
        )
        registerTool<ScopeReplaceTextPreviewArgs>(
            "scope_replace_text_preview",
            "Preview text replacement within a scope. " +
                "This computes replacement text using IntelliJ Find/Replace semantics (including regex groups and preserve-case).",
            handler = { args, request -> scopeReplaceTextPreviewHandler(args, request) },
        )
        registerTool<ScopeReplaceTextApplyArgs>(
            "scope_replace_text_apply",
            "Apply text replacement within a scope. " +
                "If occurrenceIds is empty, all found occurrences are replaced. " +
                "If occurrenceIds is provided, only those matches are replaced.",
            handler = { args, request -> scopeReplaceTextApplyHandler(args, request) },
        )
        return buildRegistration()
    }
}
