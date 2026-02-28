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

package dev.ghostflyby.mcp.gradle.common

import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTask
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskState
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.project.Project
import kotlinx.coroutines.delay
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path

internal fun resolveGradleTaskToCancel(
    project: Project,
    processingManager: ExternalSystemProcessingManager,
    taskId: Long?,
    externalProjectPath: String?,
): ExternalSystemTask {
    if (taskId != null) {
        val activeTasks = findActiveGradleExecuteTasks(project, processingManager)
        return activeTasks.firstOrNull { it.id.id == taskId }
            ?: throw McpExpectedError(
                "No active Gradle task found for taskId=$taskId. " +
                    "Active taskIds: ${activeTasks.joinToString { it.id.id.toString() }}",
            )
    }

    if (!externalProjectPath.isNullOrBlank()) {
        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetProjectPath = matchLinkedProjectPath(
            linkedProjectPaths,
            toPath(externalProjectPath, "externalProjectPath"),
        )
        val task = processingManager.findTask(
            ExternalSystemTaskType.EXECUTE_TASK,
            GradleConstants.SYSTEM_ID,
            targetProjectPath.toString(),
        )
        if (task != null && !task.state.isStopped && task.id.findProject() == project) {
            return task
        }
        throw McpExpectedError("No active Gradle execute task found for '$targetProjectPath'.")
    }

    val activeTasks = findActiveGradleExecuteTasks(project, processingManager)
    if (activeTasks.isEmpty()) {
        throw McpExpectedError("No active Gradle execute tasks found.")
    }
    if (activeTasks.size > 1) {
        throw McpExpectedError(
            "Multiple active Gradle execute tasks found. Specify taskId. " +
                "Active taskIds: ${activeTasks.joinToString { it.id.id.toString() }}",
        )
    }
    return activeTasks.first()
}

internal fun findActiveGradleExecuteTasks(
    project: Project,
    processingManager: ExternalSystemProcessingManager,
): List<ExternalSystemTask> {
    return processingManager.findTasksOfState(
        GradleConstants.SYSTEM_ID,
        ExternalSystemTaskState.NOT_STARTED,
        ExternalSystemTaskState.IN_PROGRESS,
        ExternalSystemTaskState.CANCELING,
    )
        .asSequence()
        .filter {
            it.id.type == ExternalSystemTaskType.EXECUTE_TASK &&
                !it.state.isStopped &&
                it.id.findProject() == project
        }
        .sortedBy { it.id.id }
        .toList()
}

internal fun cancelRunningExternalTask(
    project: Project,
    processingManager: ExternalSystemProcessingManager,
    taskType: ExternalSystemTaskType,
    externalProjectPath: Path,
    taskId: ExternalSystemTaskId? = null,
): Boolean {
    val task = taskId?.let { processingManager.findTask(it) }
        ?: processingManager.findTask(taskType, GradleConstants.SYSTEM_ID, externalProjectPath.toString())

    if (task == null || task.state.isStopped || task.id.findProject() != project) {
        return false
    }
    return task.cancel()
}

internal suspend fun cancelRunningExternalTaskWithRetry(
    project: Project,
    processingManager: ExternalSystemProcessingManager,
    taskType: ExternalSystemTaskType,
    externalProjectPath: Path,
    taskId: ExternalSystemTaskId? = null,
    attempts: Int = 8,
    retryDelayMillis: Long = 125,
): Boolean {
    repeat(attempts) { attempt ->
        if (cancelRunningExternalTask(project, processingManager, taskType, externalProjectPath, taskId)) {
            return true
        }
        if (attempt < attempts - 1) {
            delay(retryDelayMillis)
        }
    }
    return false
}
