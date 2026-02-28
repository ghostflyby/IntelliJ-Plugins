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

package dev.ghostflyby.mcp.gradle.execution

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputType
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.toolsets.general.ExecutionToolset
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.gradle.Bundle
import dev.ghostflyby.mcp.gradle.common.activityValue
import dev.ghostflyby.mcp.gradle.common.cancelRunningExternalTask
import dev.ghostflyby.mcp.gradle.common.cancelRunningExternalTaskWithRetry
import dev.ghostflyby.mcp.gradle.common.getLinkedGradleProjectPaths
import dev.ghostflyby.mcp.gradle.common.reportActivity
import dev.ghostflyby.mcp.gradle.common.resolveGradleTaskToCancel
import dev.ghostflyby.mcp.gradle.common.selectTargetProjectPath
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal object GradleTaskExecutionTools {
    internal suspend fun runGradleTasks(
        project: Project,
        taskNames: List<String>,
        externalProjectPath: String?,
        scriptParameters: String,
        timeoutMillis: Int,
    ): ExecutionToolset.RunConfigurationResult {
        val cleanedTaskNames = taskNames
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (cleanedTaskNames.isEmpty()) {
            throw McpExpectedError("taskNames must contain at least one Gradle task.")
        }
        if (timeoutMillis <= 0) {
            throw McpExpectedError("timeoutMillis must be greater than 0.")
        }
        reportActivity(
            Bundle.message(
                "tool.activity.gradle.run.tasks",
                cleanedTaskNames.joinToString(","),
                activityValue(externalProjectPath),
                timeoutMillis,
            ),
        )

        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetProjectPath = selectTargetProjectPath(project, linkedProjectPaths, externalProjectPath)

        val settings = ExternalSystemTaskExecutionSettings().apply {
            externalSystemIdString = GradleConstants.SYSTEM_ID.id
            this.externalProjectPath = targetProjectPath.toString()
            this.taskNames = cleanedTaskNames
            this.scriptParameters = scriptParameters.trim()
        }

        val processingManager = ExternalSystemProcessingManager.getInstance()
        val outputChunks = ConcurrentLinkedQueue<String>()
        val failureMessage = AtomicReference<String?>(null)
        val startedTaskId = AtomicReference<ExternalSystemTaskId?>(null)
        val cancelOnStart = AtomicBoolean(false)
        val timeoutCancellationRequested = AtomicBoolean(false)

        val successOrFailure = withTimeoutOrNull(timeoutMillis.toLong()) {
            suspendCancellableCoroutine { continuation ->
                val callback = object : TaskCallback {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onFailure() {
                        if (continuation.isActive) continuation.resume(false)
                    }
                }

                val listener = object : ExternalSystemTaskNotificationListener {
                    override fun onStart(projectPath: String, id: ExternalSystemTaskId) {
                        startedTaskId.set(id)
                        if (cancelOnStart.get()) {
                            val canceled = cancelRunningExternalTask(
                                project = project,
                                processingManager = processingManager,
                                taskType = ExternalSystemTaskType.EXECUTE_TASK,
                                externalProjectPath = targetProjectPath,
                                taskId = id,
                            )
                            if (canceled) {
                                timeoutCancellationRequested.set(true)
                            }
                        }
                    }

                    override fun onTaskOutput(id: ExternalSystemTaskId, text: String, outputType: ProcessOutputType) {
                        outputChunks.add(text)
                    }

                    override fun onFailure(projectPath: String, id: ExternalSystemTaskId, e: Exception) {
                        failureMessage.compareAndSet(null, e.message ?: e.toString())
                    }
                }

                continuation.invokeOnCancellation {
                    val canceled = cancelRunningExternalTask(
                        project = project,
                        processingManager = processingManager,
                        taskType = ExternalSystemTaskType.EXECUTE_TASK,
                        externalProjectPath = targetProjectPath,
                        taskId = startedTaskId.get(),
                    )
                    if (canceled) {
                        timeoutCancellationRequested.set(true)
                    } else {
                        cancelOnStart.set(true)
                    }
                }

                val spec = TaskExecutionSpec.create()
                    .withProject(project)
                    .withSystemId(GradleConstants.SYSTEM_ID)
                    .withExecutorId(DefaultRunExecutor.EXECUTOR_ID)
                    .withSettings(settings)
                    .withProgressExecutionMode(ProgressExecutionMode.IN_BACKGROUND_ASYNC)
                    .withCallback(callback)
                    .withListener(listener)
                    .build()
                ExternalSystemUtil.runTask(spec)
            }
        }

        val timedOut = successOrFailure == null
        if (timedOut && !timeoutCancellationRequested.get()) {
            val canceled = cancelRunningExternalTaskWithRetry(
                project = project,
                processingManager = processingManager,
                taskType = ExternalSystemTaskType.EXECUTE_TASK,
                externalProjectPath = targetProjectPath,
                taskId = startedTaskId.get(),
            )
            if (canceled) {
                timeoutCancellationRequested.set(true)
            }
        }
        val exitCode = when (successOrFailure) {
            true -> 0
            false -> 1
            null -> null
        }

        val outputText = buildString {
            startedTaskId.get()?.let {
                append("External task id: ")
                append(it.id)
                append('\n')
            }
            val taskOutput = outputChunks.joinToString(separator = "").trim()
            if (taskOutput.isNotBlank()) {
                append(taskOutput)
            }
            val failure = failureMessage.get()
            if (failure != null && successOrFailure == false) {
                if (isNotEmpty()) append('\n')
                append("Failure: ")
                append(failure)
            }
            if (timedOut) {
                if (isNotEmpty()) append('\n')
                append("Gradle task execution timed out after $timeoutMillis ms.")
                if (timeoutCancellationRequested.get()) {
                    append(" Cancellation requested.")
                } else {
                    append(" Unable to confirm cancellation; task may still be running.")
                }
            }
            if (isEmpty()) {
                if (successOrFailure == true) append("Gradle task execution completed successfully.")
                else append("Gradle task execution failed.")
            }
        }

        return ExecutionToolset.RunConfigurationResult(
            exitCode = exitCode,
            timedOut = timedOut,
            output = outputText,
        )
    }

    internal suspend fun cancelGradleTask(
        project: Project,
        taskId: Long?,
        externalProjectPath: String?,
    ): ExecutionToolset.RunConfigurationResult {
        reportActivity(
            Bundle.message(
                "tool.activity.gradle.cancel.task",
                taskId?.toString() ?: "<auto>",
                activityValue(externalProjectPath),
            ),
        )
        val processingManager = ExternalSystemProcessingManager.getInstance()
        val targetTask = resolveGradleTaskToCancel(project, processingManager, taskId, externalProjectPath)

        val canceled = targetTask.cancel()
        val output = buildString {
            append("taskId=")
            append(targetTask.id.id)
            append(", type=")
            append(targetTask.id.type)
            append(", state=")
            append(targetTask.state)
            if (canceled) append("\nCancellation requested.")
            else append("\nFailed to request cancellation.")
        }

        return ExecutionToolset.RunConfigurationResult(
            exitCode = if (canceled) 0 else 1,
            timedOut = false,
            output = output,
        )
    }
}
