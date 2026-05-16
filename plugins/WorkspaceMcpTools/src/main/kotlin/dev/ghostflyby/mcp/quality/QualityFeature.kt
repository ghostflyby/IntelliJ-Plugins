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

package dev.ghostflyby.mcp.quality

import dev.ghostflyby.mcp.quality.tools.QualityCodeCleanupFileArgs
import dev.ghostflyby.mcp.quality.tools.QualityCodeCleanupScopeFilesArgs
import dev.ghostflyby.mcp.quality.tools.QualityFixFileQuickArgs
import dev.ghostflyby.mcp.quality.tools.QualityFixScopeQuickArgs
import dev.ghostflyby.mcp.quality.tools.QualityFixScopeQuickByPresetArgs
import dev.ghostflyby.mcp.quality.tools.QualityGetFileProblemsArgs
import dev.ghostflyby.mcp.quality.tools.QualityGetScopeProblemsArgs
import dev.ghostflyby.mcp.quality.tools.QualityGetScopeProblemsBySeverityArgs
import dev.ghostflyby.mcp.quality.tools.QualityGetScopeProblemsBySeverityQuickArgs
import dev.ghostflyby.mcp.quality.tools.QualityGetScopeProblemsQuickArgs
import dev.ghostflyby.mcp.quality.tools.QualityListInspectionProfilesArgs
import dev.ghostflyby.mcp.quality.tools.QualityOptimizeImportsFileArgs
import dev.ghostflyby.mcp.quality.tools.QualityOptimizeImportsScopeFilesArgs
import dev.ghostflyby.mcp.quality.tools.QualityReformatFileArgs
import dev.ghostflyby.mcp.quality.tools.QualityReformatScopeFilesArgs
import dev.ghostflyby.mcp.quality.tools.qualityCodeCleanupFileHandler
import dev.ghostflyby.mcp.quality.tools.qualityCodeCleanupScopeFilesHandler
import dev.ghostflyby.mcp.quality.tools.qualityFixFileQuickHandler
import dev.ghostflyby.mcp.quality.tools.qualityFixScopeQuickByPresetHandler
import dev.ghostflyby.mcp.quality.tools.qualityFixScopeQuickHandler
import dev.ghostflyby.mcp.quality.tools.qualityGetFileProblemsHandler
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsBySeverityHandler
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsBySeverityQuickHandler
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsHandler
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsQuickHandler
import dev.ghostflyby.mcp.quality.tools.qualityListInspectionProfilesHandler
import dev.ghostflyby.mcp.quality.tools.qualityOptimizeImportsFileHandler
import dev.ghostflyby.mcp.quality.tools.qualityOptimizeImportsScopeFilesHandler
import dev.ghostflyby.mcp.quality.tools.qualityReformatFileHandler
import dev.ghostflyby.mcp.quality.tools.qualityReformatScopeFilesHandler
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

    override fun register(context: WorkspaceMcpFeatureRegistrationContext): WorkspaceMcpFeatureRegistration {
        context.registerTool<QualityGetFileProblemsArgs>("quality_get_file_problems",
            "Analyze a text file by VFS URL and return IDE highlight problems (errors only or errors+warnings).",
            handler = { args, sid -> qualityGetFileProblemsHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityGetScopeProblemsArgs>("quality_get_scope_problems",
            "Analyze files inside a resolved scope descriptor and return IDE highlight problems.",
            handler = { args, sid -> qualityGetScopeProblemsHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityGetScopeProblemsQuickArgs>("quality_get_scope_problems_quick",
            "First-call friendly scope problem analysis shortcut with preset scope.",
            handler = { args, sid -> qualityGetScopeProblemsQuickHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityReformatFileArgs>("quality_reformat_file",
            "Run IDE reformat action for a single file by VFS URL.",
            handler = { args, sid -> qualityReformatFileHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityOptimizeImportsFileArgs>("quality_optimize_imports_file",
            "Run IDE optimize-imports action for a single file by VFS URL.",
            handler = { args, sid -> qualityOptimizeImportsFileHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityReformatScopeFilesArgs>("quality_reformat_scope_files",
            "Run IDE reformat action for project-content files matched by a scope descriptor.",
            handler = { args, sid -> qualityReformatScopeFilesHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityOptimizeImportsScopeFilesArgs>("quality_optimize_imports_scope_files",
            "Run IDE optimize-imports action for project-content files matched by a scope descriptor.",
            handler = { args, sid -> qualityOptimizeImportsScopeFilesHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityGetScopeProblemsBySeverityArgs>("quality_get_scope_problems_by_severity",
            "Analyze files inside a resolved scope descriptor and return problems at or above severity threshold.",
            handler = { args, sid -> qualityGetScopeProblemsBySeverityHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityGetScopeProblemsBySeverityQuickArgs>("quality_get_scope_problems_by_severity_quick",
            "First-call friendly severity-filtered scope problem analysis shortcut.",
            handler = { args, sid -> qualityGetScopeProblemsBySeverityQuickHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityFixFileQuickArgs>("quality_fix_file_quick",
            "Run quick file fix pipeline (optimize imports + reformat) by VFS URL.",
            handler = { args, sid -> qualityFixFileQuickHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityFixScopeQuickArgs>("quality_fix_scope_quick",
            "Run quick scope fix pipeline (optimize imports + reformat) for scope files.",
            handler = { args, sid -> qualityFixScopeQuickHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityFixScopeQuickByPresetArgs>("quality_fix_scope_quick_by_preset",
            "First-call friendly quick scope fix shortcut (optimize imports + reformat) with preset scope.",
            handler = { args, sid -> qualityFixScopeQuickByPresetHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityListInspectionProfilesArgs>("quality_list_inspection_profiles",
            "List available inspection profile names and mark the current profile.",
            handler = { args, sid -> qualityListInspectionProfilesHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityCodeCleanupFileArgs>("quality_code_cleanup_file",
            "Run IDE inspection-based code cleanup for a single file by VFS URL.",
            handler = { args, sid -> qualityCodeCleanupFileHandler(args, sid, context.requestRunner) })
        context.registerTool<QualityCodeCleanupScopeFilesArgs>("quality_code_cleanup_scope_files",
            "Run IDE inspection-based code cleanup for project-content files matched by a scope descriptor.",
            handler = { args, sid -> qualityCodeCleanupScopeFilesHandler(args, sid, context.requestRunner) })
        return context.buildRegistration()
    }
}
