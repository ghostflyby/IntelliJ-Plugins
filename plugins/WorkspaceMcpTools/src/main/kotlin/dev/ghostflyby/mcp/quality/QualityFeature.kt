/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.quality

import dev.ghostflyby.mcp.quality.tools.*
import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureContext
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

/**
 * Quality SDK feature: registers all 15 quality MCP tools migrated
 * from [CodeQualityMcpTools] as SDK tools.
 */
internal class QualityFeature : WorkspaceMcpFeature {
    override val featureName: String = "quality"

    override suspend fun computeListableResources(context: WorkspaceMcpFeatureContext): List<WorkspaceListableResource> {
        return emptyList()
    }

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerTool<QualityGetFileProblemsArgs>(
            "quality_get_file_problems",
            "Analyze a text file by VFS URL and return IDE highlight problems (errors only or errors+warnings).",
            handler = { args, sid -> qualityGetFileProblemsHandler(args, sid, requestRunner) },
        )
        registerTool<QualityGetScopeProblemsArgs>(
            "quality_get_scope_problems",
            "Analyze files inside a resolved scope descriptor and return IDE highlight problems.",
            handler = { args, sid -> qualityGetScopeProblemsHandler(args, sid, requestRunner) },
        )
        registerTool<QualityGetScopeProblemsQuickArgs>(
            "quality_get_scope_problems_quick",
            "First-call friendly scope problem analysis shortcut with preset scope.",
            handler = { args, sid -> qualityGetScopeProblemsQuickHandler(args, sid, requestRunner) },
        )
        registerTool<QualityReformatFileArgs>(
            "quality_reformat_file",
            "Run IDE reformat action for a single file by VFS URL.",
            handler = { args, sid -> qualityReformatFileHandler(args, sid, requestRunner) },
        )
        registerTool<QualityOptimizeImportsFileArgs>(
            "quality_optimize_imports_file",
            "Run IDE optimize-imports action for a single file by VFS URL.",
            handler = { args, sid -> qualityOptimizeImportsFileHandler(args, sid, requestRunner) },
        )
        registerTool<QualityReformatScopeFilesArgs>(
            "quality_reformat_scope_files",
            "Run IDE reformat action for project-content files matched by a scope descriptor.",
            handler = { args, sid -> qualityReformatScopeFilesHandler(args, sid, requestRunner) },
        )
        registerTool<QualityOptimizeImportsScopeFilesArgs>(
            "quality_optimize_imports_scope_files",
            "Run IDE optimize-imports action for project-content files matched by a scope descriptor.",
            handler = { args, sid -> qualityOptimizeImportsScopeFilesHandler(args, sid, requestRunner) },
        )
        registerTool<QualityGetScopeProblemsBySeverityArgs>(
            "quality_get_scope_problems_by_severity",
            "Analyze files inside a resolved scope descriptor and return problems at or above severity threshold.",
            handler = { args, sid -> qualityGetScopeProblemsBySeverityHandler(args, sid, requestRunner) },
        )
        registerTool<QualityGetScopeProblemsBySeverityQuickArgs>(
            "quality_get_scope_problems_by_severity_quick",
            "First-call friendly severity-filtered scope problem analysis shortcut.",
            handler = { args, sid -> qualityGetScopeProblemsBySeverityQuickHandler(args, sid, requestRunner) },
        )
        registerTool<QualityFixFileQuickArgs>(
            "quality_fix_file_quick",
            "Run quick file fix pipeline (optimize imports + reformat) by VFS URL.",
            handler = { args, sid -> qualityFixFileQuickHandler(args, sid, requestRunner) },
        )
        registerTool<QualityFixScopeQuickArgs>(
            "quality_fix_scope_quick",
            "Run quick scope fix pipeline (optimize imports + reformat) for scope files.",
            handler = { args, sid -> qualityFixScopeQuickHandler(args, sid, requestRunner) },
        )
        registerTool<QualityFixScopeQuickByPresetArgs>(
            "quality_fix_scope_quick_by_preset",
            "First-call friendly quick scope fix shortcut (optimize imports + reformat) with preset scope.",
            handler = { args, sid -> qualityFixScopeQuickByPresetHandler(args, sid, requestRunner) },
        )
        registerTool<QualityListInspectionProfilesArgs>(
            "quality_list_inspection_profiles",
            "List available inspection profile names and mark the current profile.",
            handler = { args, sid -> qualityListInspectionProfilesHandler(args, sid, requestRunner) },
        )
        registerTool<QualityCodeCleanupFileArgs>(
            "quality_code_cleanup_file",
            "Run IDE inspection-based code cleanup for a single file by VFS URL.",
            handler = { args, sid -> qualityCodeCleanupFileHandler(args, sid, requestRunner) },
        )
        registerTool<QualityCodeCleanupScopeFilesArgs>(
            "quality_code_cleanup_scope_files",
            "Run IDE inspection-based code cleanup for project-content files matched by a scope descriptor.",
            handler = { args, sid -> qualityCodeCleanupScopeFilesHandler(args, sid, requestRunner) },
        )
        return buildRegistration()
    }
}
