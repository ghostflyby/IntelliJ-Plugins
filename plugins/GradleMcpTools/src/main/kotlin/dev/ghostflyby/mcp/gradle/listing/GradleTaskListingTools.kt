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

import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.gradle.Bundle
import dev.ghostflyby.mcp.gradle.GradleMcpTools
import dev.ghostflyby.mcp.gradle.common.activityValue
import dev.ghostflyby.mcp.gradle.common.getLinkedGradleProjectPaths
import dev.ghostflyby.mcp.gradle.common.reportActivity
import dev.ghostflyby.mcp.gradle.common.selectTargetPaths
import org.jetbrains.plugins.gradle.util.GradleConstants

internal object GradleTaskListingTools {
    internal suspend fun getGradleTaskDetail(
        project: Project,
        taskName: String,
        externalProjectPath: String?,
    ): GradleMcpTools.GradleTaskDetailsResult {
        val query = taskName.trim()
        if (query.isEmpty()) {
            throw McpExpectedError("taskName must not be blank.")
        }
        reportActivity(
            Bundle.message(
                "tool.activity.gradle.get.task.detail",
                query,
                activityValue(externalProjectPath),
            ),
        )
        val simpleName = query.substringAfterLast(':')
        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetPaths = selectTargetPaths(linkedProjectPaths, externalProjectPath)

        val matchedTasks = targetPaths.asSequence()
            .flatMap { path ->
                ExternalSystemApiUtil.findProjectTasks(project, GradleConstants.SYSTEM_ID, path.toString())
                    .asSequence()
                    .filter { task -> task.name == query || task.name == simpleName }
                    .map { task ->
                        GradleMcpTools.GradleTaskDetail(
                            name = task.name,
                            projectPath = path.toString(),
                            group = task.group,
                            description = task.description,
                            inherited = task.isInherited,
                            type = task.type,
                            isJvm = task.isJvm,
                            isTest = task.isTest,
                            isJvmTest = task.isJvmTest,
                        )
                    }
            }
            .distinct()
            .sortedBy { "${it.projectPath}:${it.name}" }
            .toList()

        if (matchedTasks.isEmpty()) {
            throw McpExpectedError("No Gradle task matched '$taskName'.")
        }
        return GradleMcpTools.GradleTaskDetailsResult(matchedTasks)
    }

    internal suspend fun listGradleTasks(
        project: Project,
        externalProjectPath: String?,
    ): GradleMcpTools.GradleTasksResult {
        reportActivity(
            Bundle.message(
                "tool.activity.gradle.list.tasks",
                activityValue(externalProjectPath),
            ),
        )
        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetPaths = selectTargetPaths(linkedProjectPaths, externalProjectPath)
        val includeProjectPathInOutput = targetPaths.size > 1

        val tasks = targetPaths.asSequence()
            .flatMap { path ->
                ExternalSystemApiUtil.findProjectTasks(project, GradleConstants.SYSTEM_ID, path.toString()).asSequence()
            }
            .map { task ->
                val group = task.group?.takeIf { it.isNotBlank() } ?: "other"
                val description = task.description?.takeIf { it.isNotBlank() } ?: ""
                val inheritedSuffix = if (task.isInherited) " [inherited]" else ""
                val taskProjectPath = task.linkedExternalProjectPath.takeIf { it.isNotBlank() } ?: ""
                val title = if (includeProjectPathInOutput && taskProjectPath.isNotBlank()) {
                    "${task.name} ($group)$inheritedSuffix @ $taskProjectPath"
                } else {
                    "${task.name} ($group)$inheritedSuffix"
                }
                if (description.isBlank()) title else "$title - $description"
            }
            .distinct()
            .sorted()
            .toList()

        if (tasks.isEmpty()) {
            throw McpExpectedError("No Gradle tasks found. Sync the Gradle project in IDE first.")
        }
        return GradleMcpTools.GradleTasksResult(tasks)
    }
}
