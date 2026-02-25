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

import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessOutputType
import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import com.intellij.mcpserver.toolsets.general.ExecutionToolset
import com.intellij.openapi.externalSystem.importing.ImportSpecBuilder
import com.intellij.openapi.externalSystem.model.execution.ExternalSystemTaskExecutionSettings
import com.intellij.openapi.externalSystem.model.task.*
import com.intellij.openapi.externalSystem.service.execution.ProgressExecutionMode
import com.intellij.openapi.externalSystem.service.internal.ExternalSystemProcessingManager
import com.intellij.openapi.externalSystem.task.TaskCallback
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.externalSystem.util.ExternalSystemUtil
import com.intellij.openapi.externalSystem.util.task.TaskExecutionSpec
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

@Suppress("FunctionName")
public class GradleMcpTools : McpToolset {
    private data class LinkedGradleProject(
        val path: Path,
        val gradleJvm: String?,
    )

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
    public suspend fun list_linked_gradle_projects(): LinkedGradleProjectsResult {
        val project = currentCoroutineContext().project
        return LinkedGradleProjectsResult(
            getLinkedGradleProjectPaths(project)
            .map { it.toString() }
                .sorted(),
        )
    }

    @McpTool
    @McpDescription(
        "Sync linked Gradle projects and wait until finished. Returns run-style result with exitCode/timedOut/output.",
    )
    public suspend fun sync_gradle_projects(
        @McpDescription("Optional linked Gradle project path. If omitted, sync all linked projects.")
        externalProjectPath: String? = null,
        @McpDescription("Timeout in milliseconds for each target project sync.")
        timeoutMillis: Int = DEFAULT_TIMEOUT_MS,
    ): ExecutionToolset.RunConfigurationResult {
        if (timeoutMillis <= 0) {
            throw McpExpectedError("timeoutMillis must be greater than 0.")
        }

        val project = currentCoroutineContext().project
        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetPaths = selectTargetPaths(linkedProjectPaths, externalProjectPath)
            .sortedBy { it.toString() }
        val syncLogs = mutableListOf<String>()

        for (path in targetPaths) {
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
                        null,
                        true,
                        output,
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
                        1,
                        false,
                        output,
                    )
                }
            }
        }

        return ExecutionToolset.RunConfigurationResult(
            0,
            false,
            syncLogs.joinToString("\n"),
        )
    }

    @McpTool
    @McpDescription("List linked Gradle projects detail. Returns { projects: [{path,name,isRoot,buildFile,gradleJvm}] }.")
    public suspend fun list_gradle_projects_detail(
        @McpDescription("Optional linked Gradle project path. If omitted, return details for all linked projects.")
        externalProjectPath: String? = null,
    ): GradleProjectDetailsResult {
        val project = currentCoroutineContext().project
        val linkedProjects = getLinkedGradleProjects(project)
        val selectedPaths = selectTargetPaths(linkedProjects.map { it.path }, externalProjectPath).toSet()
        val allLinkedPaths = linkedProjects.map { it.path.normalize() }.toSet()

        return GradleProjectDetailsResult(
            linkedProjects
            .filter { it.path in selectedPaths }
            .sortedBy { it.path }
            .map { linkedProject ->
                val path = linkedProject.path
                GradleProjectDetail(
                    path = path.toString(),
                    name = path.fileName?.toString() ?: path.toString(),
                    isRoot = isRootGradleProject(path, allLinkedPaths),
                    buildFile = detectBuildFile(path)?.toString(),
                    gradleJvm = linkedProject.gradleJvm,
                )
            },
        )
    }

    @McpTool
    @McpDescription(
        "Get detail for matching Gradle tasks. Returns { taskDetails: [{name,projectPath,group,description,...}] }.",
    )
    public suspend fun get_gradle_task_detail(
        @McpDescription("Task name or task path, e.g. \"build\" or \":plugins:GradleMcpTools:compileKotlin\".")
        taskName: String,
        @McpDescription("Optional linked Gradle project path. If omitted, search all linked projects.")
        externalProjectPath: String? = null,
    ): GradleTaskDetailsResult {
        val query = taskName.trim()
        if (query.isEmpty()) {
            throw McpExpectedError("taskName must not be blank.")
        }
        val simpleName = query.substringAfterLast(':')

        val project = currentCoroutineContext().project
        val linkedProjectPaths = getLinkedGradleProjectPaths(project)
        val targetPaths = selectTargetPaths(linkedProjectPaths, externalProjectPath)

        val matchedTasks = targetPaths.asSequence()
            .flatMap { path ->
                ExternalSystemApiUtil.findProjectTasks(project, GradleConstants.SYSTEM_ID, path.toString())
                    .asSequence()
                    .filter { task -> task.name == query || task.name == simpleName }
                    .map { task ->
                        GradleTaskDetail(
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
        return GradleTaskDetailsResult(matchedTasks)
    }

    @McpTool
    @McpDescription("List visible Gradle tasks. Returns { tasks: [...] }.")
    public suspend fun list_gradle_tasks(
        @McpDescription(
            "Optional linked Gradle project path. If omitted, list tasks from all linked projects.",
        )
        externalProjectPath: String? = null,
    ): GradleTasksResult {
        val project = currentCoroutineContext().project
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
        return GradleTasksResult(tasks)
    }

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

        val project = currentCoroutineContext().project
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
                                project,
                                processingManager,
                                ExternalSystemTaskType.EXECUTE_TASK,
                                targetProjectPath,
                                id,
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
                        project,
                        processingManager,
                        ExternalSystemTaskType.EXECUTE_TASK,
                        targetProjectPath,
                        startedTaskId.get(),
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
                    .withProgressExecutionMode(ProgressExecutionMode.NO_PROGRESS_ASYNC)
                    .withCallback(callback)
                    .withListener(listener)
                    .build()
                ExternalSystemUtil.runTask(spec)
            }
        }

        val timedOut = successOrFailure == null
        if (timedOut && !timeoutCancellationRequested.get()) {
            val canceled = cancelRunningExternalTaskWithRetry(
                project,
                processingManager,
                ExternalSystemTaskType.EXECUTE_TASK,
                targetProjectPath,
                startedTaskId.get(),
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
            exitCode,
            timedOut,
            outputText,
        )
    }

    @McpTool
    @McpDescription("Cancel active Gradle execute task. Returns run-style result with cancellation status in output.")
    public suspend fun cancel_gradle_task(
        @McpDescription("Optional external system task id. If omitted, exactly one active task must exist.")
        taskId: Long? = null,
        @McpDescription("Optional linked Gradle project path used when taskId is omitted.")
        externalProjectPath: String? = null,
    ): ExecutionToolset.RunConfigurationResult {
        val project = currentCoroutineContext().project
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
            if (canceled) 0 else 1,
            false,
            output,
        )
    }

    private fun getLinkedGradleProjects(project: Project): List<LinkedGradleProject> {
        val linkedProjects = GradleSettings.getInstance(project).linkedProjectsSettings
            .asSequence()
            .filter { it.externalProjectPath.isNotBlank() }
            .map { projectSettings ->
                LinkedGradleProject(
                    path = toPath(projectSettings.externalProjectPath, "linked Gradle project path"),
                    gradleJvm = projectSettings.gradleJvm,
                )
            }
            .distinctBy { it.path.normalize().toString() }
            .toList()
        if (linkedProjects.isEmpty()) {
            throw McpExpectedError("No linked Gradle projects found in current IDE project.")
        }
        return linkedProjects
    }

    private fun getLinkedGradleProjectPaths(project: Project): List<Path> {
        return getLinkedGradleProjects(project).map { it.path }
    }

    private fun selectTargetPaths(linkedProjectPaths: List<Path>, externalProjectPath: String?): List<Path> {
        if (externalProjectPath.isNullOrBlank()) return linkedProjectPaths
        val requestedPath = toPath(externalProjectPath, "externalProjectPath")
        return listOf(matchLinkedProjectPath(linkedProjectPaths, requestedPath))
    }

    private fun selectTargetProjectPath(
        project: Project,
        linkedProjectPaths: List<Path>,
        externalProjectPath: String?,
    ): Path {
        if (!externalProjectPath.isNullOrBlank()) {
            return matchLinkedProjectPath(linkedProjectPaths, toPath(externalProjectPath, "externalProjectPath"))
        }
        if (linkedProjectPaths.size == 1) {
            return linkedProjectPaths.first()
        }

        val basePath = project.basePath?.let { toPathOrNull(it) }
        if (basePath != null) {
            val normalizedBasePath = basePath.normalize()
            val matched = linkedProjectPaths.firstOrNull { it.normalize() == normalizedBasePath }
            if (matched != null) {
                return matched
            }
        }

        throw McpExpectedError(
            "Multiple linked Gradle projects found. Specify externalProjectPath. " +
                    "Available values: ${linkedProjectPaths.joinToString { it.toString() }}",
        )
    }

    private fun matchLinkedProjectPath(linkedProjectPaths: List<Path>, requestedPath: Path): Path {
        val normalizedRequestedPath = requestedPath.normalize()
        val normalizedLinkedProjectPaths = linkedProjectPaths.map { it to it.normalize() }

        val exactMatches = normalizedLinkedProjectPaths
            .filter { (_, linkedPath) -> linkedPath == normalizedRequestedPath }
            .map { (originalPath, _) -> originalPath }
        if (exactMatches.any()) {
            return exactMatches.singleOrNull() ?: throw McpExpectedError(
                "Ambiguous Gradle project path '$requestedPath'. " +
                        "Matched values: ${exactMatches.joinToString { it.toString() }}. " +
                        "Please specify a full linked path.",
            )
        }

        val suffixMatches = normalizedLinkedProjectPaths
            .filter { (_, linkedPath) -> linkedPath.endsWith(normalizedRequestedPath) }
            .map { (originalPath, _) -> originalPath }
        if (suffixMatches.any()) {
            return suffixMatches.singleOrNull() ?: throw McpExpectedError(
                "Ambiguous Gradle project path '$requestedPath'. " +
                        "Matched values: ${suffixMatches.joinToString { it.toString() }}. " +
                        "Please specify a full linked path.",
            )
        }

        throw McpExpectedError(
            "Gradle project path '$requestedPath' is not linked. " +
                    "Available values: ${linkedProjectPaths.joinToString { it.toString() }}",
        )
    }

    private fun resolveGradleTaskToCancel(
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

    private fun findActiveGradleExecuteTasks(
        project: Project,
        processingManager: ExternalSystemProcessingManager,
    ): List<ExternalSystemTask> {
        return findActiveGradleTasks(project, processingManager, ExternalSystemTaskType.EXECUTE_TASK)
    }

    private fun findActiveGradleTasks(
        project: Project,
        processingManager: ExternalSystemProcessingManager,
        taskType: ExternalSystemTaskType,
    ): List<ExternalSystemTask> {
        return processingManager.findTasksOfState(
            GradleConstants.SYSTEM_ID,
            ExternalSystemTaskState.NOT_STARTED,
            ExternalSystemTaskState.IN_PROGRESS,
            ExternalSystemTaskState.CANCELING,
        )
            .asSequence()
            .filter {
                it.id.type == taskType &&
                        !it.state.isStopped &&
                        it.id.findProject() == project
            }
            .sortedBy { it.id.id }
            .toList()
    }

    private fun cancelRunningExternalTask(
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

    private suspend fun cancelRunningExternalTaskWithRetry(
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

    private suspend fun isRootGradleProject(path: Path, allLinkedPaths: Set<Path>): Boolean {
        val isRoot = withContext(Dispatchers.IO) {
            Files.exists(path.resolve("settings.gradle.kts")) || Files.exists(path.resolve("settings.gradle"))
        }
        if (isRoot) return true
        return allLinkedPaths.none { otherPath ->
            otherPath != path.normalize() && path.normalize().startsWith(otherPath)
        }
    }

    private suspend fun detectBuildFile(path: Path): Path? {
        val candidates = listOf("build.gradle.kts", "build.gradle")
        return candidates.asSequence()
            .map { path.resolve(it) }
            .firstOrNull { withContext(Dispatchers.IO) { Files.isRegularFile(it) } }
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
        val outcome: Result<Unit> = awaitFuture(importFuture, timeoutMillis)
            ?: return FutureWaitResult.Timeout(
                cancellationRequested = cancelRunningExternalTaskWithRetry(
                    project,
                    processingManager,
                    ExternalSystemTaskType.RESOLVE_PROJECT,
                    externalProjectPath,
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

    private fun toPath(pathText: String, paramName: String): Path {
        return toPathOrNull(pathText)
            ?: throw McpExpectedError("Invalid path for $paramName: '$pathText'")
    }

    private fun toPathOrNull(pathText: String): Path? {
        return try {
            Path.of(pathText).normalize()
        } catch (_: InvalidPathException) {
            null
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 600_000
    }
}
