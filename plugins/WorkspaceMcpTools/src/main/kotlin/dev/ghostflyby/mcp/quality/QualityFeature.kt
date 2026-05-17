/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.quality

import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import dev.ghostflyby.mcp.quality.tools.*
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Quality SDK feature: registers all 15 quality MCP tools migrated
 * from [CodeQualityMcpTools] as SDK tools.
 */
internal class QualityFeature : WorkspaceMcpFeature {
    override val featureName: String = "quality"

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerTool<QualityGetFileProblemsArgs>(
            "quality_get_file_problems",
            "Analyze a text file by VFS URL and return IDE highlight problems (errors only or errors+warnings).",
            schema = QualityGetFileProblemsArgs::class.jsonSchema,
            handler = { args, request -> qualityGetFileProblemsHandler(args, request) },
        )
        registerTool<QualityGetScopeProblemsArgs>(
            "quality_get_scope_problems",
            "Analyze files inside a resolved scope descriptor and return IDE highlight problems.",
            schema = QualityGetScopeProblemsArgs::class.jsonSchema,
            handler = { args, request -> qualityGetScopeProblemsHandler(args, request) },
        )
        registerTool<QualityGetScopeProblemsQuickArgs>(
            "quality_get_scope_problems_quick",
            "First-call friendly scope problem analysis shortcut with preset scope.",
            schema = QualityGetScopeProblemsQuickArgs::class.jsonSchema,
            handler = { args, request -> qualityGetScopeProblemsQuickHandler(args, request) },
        )
        registerTool<QualityReformatFileArgs>(
            "quality_reformat_file",
            "Run IDE reformat action for a single file by VFS URL.",
            schema = QualityReformatFileArgs::class.jsonSchema,
            handler = { args, request -> qualityReformatFileHandler(args, request) },
        )
        registerTool<QualityOptimizeImportsFileArgs>(
            "quality_optimize_imports_file",
            "Run IDE optimize-imports action for a single file by VFS URL.",
            schema = QualityOptimizeImportsFileArgs::class.jsonSchema,
            handler = { args, request -> qualityOptimizeImportsFileHandler(args, request) },
        )
        registerTool<QualityReformatScopeFilesArgs>(
            "quality_reformat_scope_files",
            "Run IDE reformat action for project-content files matched by a scope descriptor.",
            schema = QualityReformatScopeFilesArgs::class.jsonSchema,
            handler = { args, request -> qualityReformatScopeFilesHandler(args, request) },
        )
        registerTool<QualityOptimizeImportsScopeFilesArgs>(
            "quality_optimize_imports_scope_files",
            "Run IDE optimize-imports action for project-content files matched by a scope descriptor.",
            schema = QualityOptimizeImportsScopeFilesArgs::class.jsonSchema,
            handler = { args, request -> qualityOptimizeImportsScopeFilesHandler(args, request) },
        )
        registerTool<QualityGetScopeProblemsBySeverityArgs>(
            "quality_get_scope_problems_by_severity",
            "Analyze files inside a resolved scope descriptor and return problems at or above severity threshold.",
            schema = QualityGetScopeProblemsBySeverityArgs::class.jsonSchema,
            handler = { args, request -> qualityGetScopeProblemsBySeverityHandler(args, request) },
        )
        registerTool<QualityGetScopeProblemsBySeverityQuickArgs>(
            "quality_get_scope_problems_by_severity_quick",
            "First-call friendly severity-filtered scope problem analysis shortcut.",
            schema = QualityGetScopeProblemsBySeverityQuickArgs::class.jsonSchema,
            handler = { args, request -> qualityGetScopeProblemsBySeverityQuickHandler(args, request) },
        )
        registerTool<QualityFixFileQuickArgs>(
            "quality_fix_file_quick",
            "Run quick file fix pipeline (optimize imports + reformat) by VFS URL.",
            schema = QualityFixFileQuickArgs::class.jsonSchema,
            handler = { args, request -> qualityFixFileQuickHandler(args, request) },
        )
        registerTool<QualityFixScopeQuickArgs>(
            "quality_fix_scope_quick",
            "Run quick scope fix pipeline (optimize imports + reformat) for scope files.",
            schema = QualityFixScopeQuickArgs::class.jsonSchema,
            handler = { args, request -> qualityFixScopeQuickHandler(args, request) },
        )
        registerTool<QualityFixScopeQuickByPresetArgs>(
            "quality_fix_scope_quick_by_preset",
            "First-call friendly quick scope fix shortcut (optimize imports + reformat) with preset scope.",
            schema = QualityFixScopeQuickByPresetArgs::class.jsonSchema,
            handler = { args, request -> qualityFixScopeQuickByPresetHandler(args, request) },
        )
        registerTool<QualityListInspectionProfilesArgs>(
            "quality_list_inspection_profiles",
            "List available inspection profile names and mark the current profile.",
            schema = QualityListInspectionProfilesArgs::class.jsonSchema,
            handler = { args, request -> qualityListInspectionProfilesHandler(args, request) },
        )
        registerTool<QualityCodeCleanupFileArgs>(
            "quality_code_cleanup_file",
            "Run IDE inspection-based code cleanup for a single file by VFS URL.",
            schema = QualityCodeCleanupFileArgs::class.jsonSchema,
            handler = { args, request -> qualityCodeCleanupFileHandler(args, request) },
        )
        registerTool<QualityCodeCleanupScopeFilesArgs>(
            "quality_code_cleanup_scope_files",
            "Run IDE inspection-based code cleanup for project-content files matched by a scope descriptor.",
            schema = QualityCodeCleanupScopeFilesArgs::class.jsonSchema,
            handler = { args, request -> qualityCodeCleanupScopeFilesHandler(args, request) },
        )
        return buildRegistration()
    }
}
