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

package dev.ghostflyby.mcp.gradle.sync

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.toolsets.general.ExecutionToolset
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.project.Project
import dev.ghostflyby.mcp.gradle.Bundle
import dev.ghostflyby.mcp.gradle.common.activityValue
import dev.ghostflyby.mcp.gradle.common.cancelRunningExternalTaskWithRetry
import dev.ghostflyby.mcp.gradle.common.getLinkedGradleProjectPaths
import dev.ghostflyby.mcp.gradle.common.reportActivity
import dev.ghostflyby.mcp.gradle.common.selectTargetPaths
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume

internal object GradleProjectSyncTools {
    internal suspend fun syncGradleProjects(
        project: Project,
        externalProjectPath: String?,
        timeoutMillis: Int,
    ): ExecutionToolset.RunConfigurationResult {
        if (timeoutMillis <= 0) {
            throw McpExpectedError("timeoutMillis must be greater than 0.")
        }
        reportActivity(
            Bundle.message(
                "tool.activity.gradle.sync.projects",
                activityValue(externalProjectPath),
                timeoutMillis,
            ),
        )

        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetPaths = selectTargetPaths(linkedProjectPaths, externalProjectPath)
            .sortedBy { it.toString() }
        val syncLogs = mutableListOf<String>()

        for ((index, path) in targetPaths.withIndex()) {
            reportActivity(
                Bundle.message(
                    "tool.activity.gradle.sync.project.step",
                    index + 1,
                    targetPaths.size,
                    path.toString(),
                ),
            )
            when (val result = requestImportAndWait(project, path, timeoutMillis)) {
                is FutureWaitResult.Success -> syncLogs += "Synced: $path"
                is FutureWaitResult.Timeout -> {
                    val output = buildString {
                        append("Gradle sync timed out for $path after $timeoutMillis ms.")
                        if (result.cancellationRequested) {
                            append(" Cancellation requested.")
                        } else {
                            append(" Unable to confirm cancellation; sync may still be running.")
                        }
                        if (syncLogs.isNotEmpty()) {
                            append('\n')
                            append(syncLogs.joinToString("\n"))
                        }
                    }
                    return ExecutionToolset.RunConfigurationResult(
                        exitCode = null,
                        timedOut = true,
                        output = output,
                    )
                }

                is FutureWaitResult.Failure -> {
                    val output = buildString {
                        append("Gradle sync failed for $path: ${result.throwable.message ?: result.throwable}")
                        if (syncLogs.isNotEmpty()) {
                            append('\n')
                            append(syncLogs.joinToString("\n"))
                        }
                    }
                    return ExecutionToolset.RunConfigurationResult(
                        exitCode = 1,
                        timedOut = false,
                        output = output,
                    )
                }
            }
        }

        return ExecutionToolset.RunConfigurationResult(
            exitCode = 0,
            timedOut = false,
            output = syncLogs.joinToString("\n"),
        )
    }

    private suspend fun requestImportAndWait(
        project: Project,
        externalProjectPath: Path,
        timeoutMillis: Int,
    ): FutureWaitResult {
        val processingManager = ExternalSystemProcessingManager.getInstance()
        val importFuture = CompletableFuture<Void>()
        val importSpec = ImportSpecBuilder(project, GradleConstants.SYSTEM_ID)
            .withCallback(importFuture)
            .build()
        ExternalSystemUtil.refreshProject(externalProjectPath.toString(), importSpec)
        val outcome = awaitFuture(importFuture, timeoutMillis)
            ?: return FutureWaitResult.Timeout(
                cancellationRequested = cancelRunningExternalTaskWithRetry(
                    project = project,
                    processingManager = processingManager,
                    taskType = ExternalSystemTaskType.RESOLVE_PROJECT,
                    externalProjectPath = externalProjectPath,
                ),
            )
        return outcome.fold(
            onSuccess = { FutureWaitResult.Success },
            onFailure = { FutureWaitResult.Failure(it) },
        )
    }

    private suspend fun awaitFuture(
        future: CompletableFuture<Void>,
        timeoutMillis: Int,
    ): Result<Unit>? {
        return withTimeoutOrNull(timeoutMillis.toLong()) {
            suspendCancellableCoroutine { continuation ->
                future.whenComplete { _, throwable ->
                    if (!continuation.isActive) return@whenComplete
                    if (throwable == null) {
                        continuation.resume(Result.success(Unit))
                    } else {
                        continuation.resume(Result.failure(throwable))
                    }
                }
                continuation.invokeOnCancellation {
                    future.cancel(true)
                }
            }
        }
    }

    private sealed class FutureWaitResult {
        object Success : FutureWaitResult()

        data class Timeout(val cancellationRequested: Boolean) : FutureWaitResult()

        data class Failure(val throwable: Throwable) : FutureWaitResult()
    }
}
