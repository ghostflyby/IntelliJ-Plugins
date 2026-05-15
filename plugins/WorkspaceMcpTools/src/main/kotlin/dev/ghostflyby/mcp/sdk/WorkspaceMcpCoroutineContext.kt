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

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

// ---- context elements ----

internal data class WorkspaceMcpCallContext(
    val sessionId: String?,
    val instanceKey: String,
    val roots: WorkspaceMcpRootsSnapshot?,
) : AbstractCoroutineContextElement(WorkspaceMcpCallContext) {
    companion object Key : CoroutineContext.Key<WorkspaceMcpCallContext>
}

internal class WorkspaceMcpRootsSnapshot(
    val rootUris: List<String>,
)

internal data class WorkspaceMcpProjectContext(
    val projectKey: String,
    val project: Project,
    val reason: WorkspaceProjectResolutionReason,
) : AbstractCoroutineContextElement(WorkspaceMcpProjectContext) {
    companion object Key : CoroutineContext.Key<WorkspaceMcpProjectContext>
}

internal data class WorkspaceMcpProjectLifetimeContext(
    val projectKey: String,
    val project: Project,
    val lifetimeJob: Job,
) : AbstractCoroutineContextElement(WorkspaceMcpProjectLifetimeContext) {
    companion object Key : CoroutineContext.Key<WorkspaceMcpProjectLifetimeContext>
}

internal enum class WorkspaceProjectResolutionReason {
    EXPLICIT_PROJECT_KEY,
    EXPLICIT_PROJECT_PATH,
    EXPLICIT_PROJECT_NAME,
    RAW_VFS_URL,
    RELATIVE_PATH,
    SINGLE_OPEN_PROJECT,
}

// ---- nullable getters ----

internal val CoroutineContext.workspaceMcpCall: WorkspaceMcpCallContext?
    get() = this[WorkspaceMcpCallContext]

internal val CoroutineContext.workspaceMcpProject: WorkspaceMcpProjectContext?
    get() = this[WorkspaceMcpProjectContext]

internal val CoroutineContext.workspaceMcpProjectLifetime: WorkspaceMcpProjectLifetimeContext?
    get() = this[WorkspaceMcpProjectLifetimeContext]

// ---- throwing helpers ----

internal val CoroutineContext.requireWorkspaceMcpCallContext: WorkspaceMcpCallContext
    get() = workspaceMcpCall ?: error("WorkspaceMcpCallContext is not present in the current coroutine context.")

internal val CoroutineContext.requireWorkspaceMcpProjectContext: WorkspaceMcpProjectContext
    get() = workspaceMcpProject ?: error("WorkspaceMcpProjectContext is not present in the current coroutine context.")

internal val CoroutineContext.requireWorkspaceMcpProjectLifetime: WorkspaceMcpProjectLifetimeContext
    get() = workspaceMcpProjectLifetime
        ?: error("WorkspaceMcpProjectLifetimeContext is not present in the current coroutine context.")

internal val CoroutineContext.currentWorkspaceProject: Project
    get() {
        val projectCtx = requireWorkspaceMcpProjectContext
        if (projectCtx.project.isDisposed) {
            throw CancellationException(
                "Project '" + projectCtx.projectKey + "' is disposed, " +
                    "currentWorkspaceProject is not available",
            )
        }
        val lifetimeCtx = workspaceMcpProjectLifetime
        if (lifetimeCtx != null && !lifetimeCtx.lifetimeJob.isActive) {
            throw CancellationException(
                "Project '" + projectCtx.projectKey + "' lifetime job is cancelled, " +
                    "currentWorkspaceProject is not available",
            )
        }
        return projectCtx.project
    }

// ---- install helpers (context builder) ----

internal fun CoroutineContext.withWorkspaceMcpCallContext(
    sessionId: String? = null,
    instanceKey: String,
    roots: WorkspaceMcpRootsSnapshot? = null,
): CoroutineContext {
    return this + WorkspaceMcpCallContext(sessionId = sessionId, instanceKey = instanceKey, roots = roots)
}

internal fun CoroutineContext.withResolvedWorkspaceProject(
    projectKey: String,
    project: Project,
    reason: WorkspaceProjectResolutionReason,
    lifetimeJob: Job? = null,
): CoroutineContext {
    var ctx: CoroutineContext = this + WorkspaceMcpProjectContext(
        projectKey = projectKey, project = project, reason = reason,
    )
    if (lifetimeJob != null) {
        ctx = ctx + WorkspaceMcpProjectLifetimeContext(
            projectKey = projectKey, project = project, lifetimeJob = lifetimeJob,
        )
    }
    return ctx
}

internal class WorkspaceProjectLifetimeRegistry {
    private val lock = Any()
    private val jobMap = mutableMapOf<String, Job>()

    fun getOrCreateJob(project: Project): Job {
        val key = workspaceProjectKey(project)
        synchronized(lock) {
            val existing = jobMap[key]
            if (existing != null && existing.isActive) return existing
            val newJob = Job()
            jobMap[key] = newJob
            newJob.invokeOnCompletion {
                synchronized(lock) { jobMap.remove(key, newJob) }
            }
            return newJob
        }
    }

    fun cancelProject(project: Project) {
        val key = workspaceProjectKey(project)
        synchronized(lock) { jobMap.remove(key)?.cancel() }
    }

    fun getActiveJob(project: Project): Job? {
        val key = workspaceProjectKey(project)
        synchronized(lock) {
            val job = jobMap[key]
            return if (job != null && job.isActive) job else null
        }
    }
}

// ---- suspend wrappers ----

internal suspend fun <T> withWorkspaceMcpCallContext(
    sessionId: String? = null,
    instanceKey: String,
    roots: WorkspaceMcpRootsSnapshot? = null,
    block: suspend CoroutineScope.() -> T,
): T {
    return withContext(EmptyCoroutineContext.withWorkspaceMcpCallContext(sessionId = sessionId, instanceKey = instanceKey, roots = roots)) {
        block()
    }
}

internal suspend fun <T> withResolvedWorkspaceProject(
    projectKey: String,
    project: Project,
    reason: WorkspaceProjectResolutionReason,
    block: suspend CoroutineScope.() -> T,
): T {
    if (project.isDisposed) {
        throw CancellationException(
            "Cannot resolve workspace project '$projectKey': project is already disposed",
        )
    }
    val lifetimeJob = projectLifetimeRegistry.getOrCreateJob(project)
    val parentJob = currentCoroutineContext()[Job]
    val requestJob = Job(parentJob)
    val handle = lifetimeJob.invokeOnCompletion { cause ->
        requestJob.cancel(
            CancellationException(
                "Project lifetime job cancelled, cancelling request",
                cause,
            ),
        )
    }
    try {
        return withContext(
            requestJob + EmptyCoroutineContext.withResolvedWorkspaceProject(
                projectKey = projectKey,
                project = project,
                reason = reason,
                lifetimeJob = lifetimeJob,
            ),
        ) {
            block()
        }
    } finally {
        handle.dispose()
    }
}

internal suspend fun currentWorkspaceProject(): Project {
    return currentCoroutineContext().currentWorkspaceProject
}

internal val projectLifetimeRegistry = WorkspaceProjectLifetimeRegistry()
