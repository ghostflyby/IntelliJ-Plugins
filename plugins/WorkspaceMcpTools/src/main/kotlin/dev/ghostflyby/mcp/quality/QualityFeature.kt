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

import dev.ghostflyby.mcp.quality.tools.qualityCodeCleanupFileSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityCodeCleanupScopeFilesSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityFixFileQuickSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityFixScopeQuickByPresetSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityFixScopeQuickSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityGetFileProblemsSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsBySeverityQuickSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsBySeveritySdkTool
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsQuickSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityGetScopeProblemsSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityListInspectionProfilesSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityOptimizeImportsFileSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityOptimizeImportsScopeFilesSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityReformatFileSdkTool
import dev.ghostflyby.mcp.quality.tools.qualityReformatScopeFilesSdkTool
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
        context.registerTool(qualityGetFileProblemsSdkTool())
        context.registerTool(qualityGetScopeProblemsSdkTool())
        context.registerTool(qualityGetScopeProblemsQuickSdkTool())
        context.registerTool(qualityReformatFileSdkTool())
        context.registerTool(qualityOptimizeImportsFileSdkTool())
        context.registerTool(qualityReformatScopeFilesSdkTool())
        context.registerTool(qualityOptimizeImportsScopeFilesSdkTool())
        context.registerTool(qualityGetScopeProblemsBySeveritySdkTool())
        context.registerTool(qualityGetScopeProblemsBySeverityQuickSdkTool())
        context.registerTool(qualityFixFileQuickSdkTool())
        context.registerTool(qualityFixScopeQuickSdkTool())
        context.registerTool(qualityFixScopeQuickByPresetSdkTool())
        context.registerTool(qualityListInspectionProfilesSdkTool())
        context.registerTool(qualityCodeCleanupFileSdkTool())
        context.registerTool(qualityCodeCleanupScopeFilesSdkTool())

        return context.buildRegistration()
    }
}
