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

package dev.ghostflyby.mcp.gradle

import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.mcpserver.toolsets.general.ExecutionToolset
import dev.ghostflyby.mcp.gradle.execution.GradleTaskExecutionTools
import dev.ghostflyby.mcp.gradle.listing.GradleProjectListingTools
import dev.ghostflyby.mcp.gradle.listing.GradleTaskListingTools
import dev.ghostflyby.mcp.gradle.sync.GradleProjectSyncTools
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.serialization.Serializable

@Suppress("FunctionName")
public class GradleMcpTools : McpToolset {
    @Serializable
    public data class GradleProjectDetail(
        val path: String,
        val name: String,
        val isRoot: Boolean,
        val buildFile: String?,
        val gradleJvm: String?,
    )

    @Serializable
    public data class GradleTaskDetail(
        val name: String,
        val projectPath: String,
        val group: String?,
        val description: String?,
        val inherited: Boolean,
        val type: String?,
        val isJvm: Boolean,
        val isTest: Boolean,
        val isJvmTest: Boolean,
    )

    @Serializable
    public data class LinkedGradleProjectsResult(
        val projects: List<String>,
    )

    @Serializable
    public data class GradleProjectDetailsResult(
        val projects: List<GradleProjectDetail>,
    )

    @Serializable
    public data class GradleTaskDetailsResult(
        val taskDetails: List<GradleTaskDetail>,
    )

    @Serializable
    public data class GradleTasksResult(
        val tasks: List<String>,
    )

    @McpTool
    @McpDescription("List linked Gradle project paths in current IDE project. Returns { projects: [...] }.")
    public suspend fun list_linked_gradle_projects(): LinkedGradleProjectsResult =
        GradleProjectListingTools.listLinkedGradleProjects(currentCoroutineContext().project)

    @McpTool
    @McpDescription(
        "Sync linked Gradle projects and wait until finished. Returns run-style result with exitCode/timedOut/output.",
    )
    public suspend fun sync_gradle_projects(
        @McpDescription("Optional linked Gradle project path. If omitted, sync all linked projects.")
        externalProjectPath: String? = null,
        @McpDescription("Timeout in milliseconds for each target project sync.")
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    ): ExecutionToolset.RunConfigurationResult =
        GradleProjectSyncTools.syncGradleProjects(
            project = currentCoroutineContext().project,
            externalProjectPath = externalProjectPath,
            timeoutMillis = timeoutMillis,
        )

    @McpTool
    @McpDescription("List linked Gradle projects detail. Returns { projects: [{path,name,isRoot,buildFile,gradleJvm}] }.")
    public suspend fun list_gradle_projects_detail(
        @McpDescription("Optional linked Gradle project path. If omitted, return details for all linked projects.")
        externalProjectPath: String? = null,
    ): GradleProjectDetailsResult =
        GradleProjectListingTools.listGradleProjectsDetail(
            project = currentCoroutineContext().project,
            externalProjectPath = externalProjectPath,
        )

    @McpTool
    @McpDescription(
        "Get detail for matching Gradle tasks. Returns { taskDetails: [{name,projectPath,group,description,...}] }.",
    )
    public suspend fun get_gradle_task_detail(
        @McpDescription("Task name or task path, e.g. \"build\" or \":plugins:GradleMcpTools:compileKotlin\".")
        taskName: String,
        @McpDescription("Optional linked Gradle project path. If omitted, search all linked projects.")
        externalProjectPath: String? = null,
    ): GradleTaskDetailsResult =
        GradleTaskListingTools.getGradleTaskDetail(
            project = currentCoroutineContext().project,
            taskName = taskName,
            externalProjectPath = externalProjectPath,
        )

    @McpTool
    @McpDescription("List visible Gradle tasks. Returns { tasks: [...] }.")
    public suspend fun list_gradle_tasks(
        @McpDescription(
            "Optional linked Gradle project path. If omitted, list tasks from all linked projects.",
        )
        externalProjectPath: String? = null,
    ): GradleTasksResult =
        GradleTaskListingTools.listGradleTasks(
            project = currentCoroutineContext().project,
            externalProjectPath = externalProjectPath,
        )

    @McpTool
    @McpDescription(
        "Run arbitrary Gradle tasks via IntelliJ Gradle external system (same backend as Gradle tool window).",
    )
    public suspend fun run_gradle_tasks(
        @McpDescription("Gradle task names, for example [\":app:build\"] or [\"clean\", \"test\"].")
        taskNames: List<String>,
        @McpDescription(
            "Optional linked Gradle project path. Required when multiple linked Gradle projects exist.",
        )
        externalProjectPath: String? = null,
        @McpDescription("Additional Gradle script parameters, e.g. \"--info --stacktrace\".")
        scriptParameters: String = "",
        @McpDescription("Timeout in milliseconds to wait for completion. Timeout triggers cancellation request.")
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    ): ExecutionToolset.RunConfigurationResult =
        GradleTaskExecutionTools.runGradleTasks(
            project = currentCoroutineContext().project,
            taskNames = taskNames,
            externalProjectPath = externalProjectPath,
            scriptParameters = scriptParameters,
            timeoutMillis = timeoutMillis,
        )

    @McpTool
    @McpDescription("Cancel active Gradle execute task. Returns run-style result with cancellation status in output.")
    public suspend fun cancel_gradle_task(
        @McpDescription("Optional external system task id. If omitted, exactly one active task must exist.")
        taskId: Long? = null,
        @McpDescription("Optional linked Gradle project path used when taskId is omitted.")
        externalProjectPath: String? = null,
    ): ExecutionToolset.RunConfigurationResult =
        GradleTaskExecutionTools.cancelGradleTask(
            project = currentCoroutineContext().project,
            taskId = taskId,
            externalProjectPath = externalProjectPath,
        )

    private companion object {
        const val DEFAULT_TIMEOUT_MS: Int = dev.ghostflyby.mcp.gradle.common.DEFAULT_TIMEOUT_MS
    }
}
