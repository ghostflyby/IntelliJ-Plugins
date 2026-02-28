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

package dev.ghostflyby.mcp.gradle.listing

import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.gradle.Bundle
import dev.ghostflyby.mcp.gradle.GradleMcpTools
import dev.ghostflyby.mcp.gradle.common.activityValue
import dev.ghostflyby.mcp.gradle.common.detectBuildFile
import dev.ghostflyby.mcp.gradle.common.getLinkedGradleProjectPaths
import dev.ghostflyby.mcp.gradle.common.getLinkedGradleProjects
import dev.ghostflyby.mcp.gradle.common.isRootGradleProject
import dev.ghostflyby.mcp.gradle.common.reportActivity
import dev.ghostflyby.mcp.gradle.common.selectTargetPaths

internal object GradleProjectListingTools {
    internal suspend fun listLinkedGradleProjects(project: Project): GradleMcpTools.LinkedGradleProjectsResult {
        reportActivity(Bundle.message("tool.activity.gradle.list.linked.projects"))
        return GradleMcpTools.LinkedGradleProjectsResult(
            getLinkedGradleProjectPaths(project)
                .map { it.toString() }
                .sorted(),
        )
    }

    internal suspend fun listGradleProjectsDetail(
        project: Project,
        externalProjectPath: String?,
    ): GradleMcpTools.GradleProjectDetailsResult {
        reportActivity(
            Bundle.message(
                "tool.activity.gradle.list.projects.detail",
                activityValue(externalProjectPath),
            ),
        )
        val linkedProjects = getLinkedGradleProjects(project)
        val selectedPaths = selectTargetPaths(linkedProjects.map { it.path }, externalProjectPath).toSet()
        val allLinkedPaths = linkedProjects.map { it.path.normalize() }.toSet()

        return GradleMcpTools.GradleProjectDetailsResult(
            linkedProjects
                .filter { it.path in selectedPaths }
                .sortedBy { it.path }
                .map { linkedProject ->
                    val path = linkedProject.path
                    GradleMcpTools.GradleProjectDetail(
                        path = path.toString(),
                        name = path.fileName?.toString() ?: path.toString(),
                        isRoot = isRootGradleProject(path, allLinkedPaths),
                        buildFile = detectBuildFile(path)?.toString(),
                        gradleJvm = linkedProject.gradleJvm,
                    )
                },
        )
    }
}
