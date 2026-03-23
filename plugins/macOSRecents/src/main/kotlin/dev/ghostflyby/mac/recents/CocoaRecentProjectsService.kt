/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

package dev.ghostflyby.mac.recents

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

@Service(Service.Level.APP)
internal class CocoaRecentProjectsSyncService(
    private val coroutineScope: CoroutineScope,
) {
    private val coordinator = CocoaRecentProjectsCoordinator(
        coroutineScope = coroutineScope,
        documentsBridge = FoundationCocoaRecentDocumentsBridge(),
        onFailure = { throwable ->
            LOG.warn("Failed to update macOS recent documents.", throwable)
        },
    )

    internal fun scheduleSync(recentPaths: List<String>, startupProjectPath: String? = null) {
        coordinator.scheduleSync(recentPaths = recentPaths, startupProjectPath = startupProjectPath)
    }

    private companion object {
        private val LOG = Logger.getInstance(CocoaRecentProjectsSyncService::class.java)
    }
}

internal class CocoaRecentProjectsCoordinator(
    private val coroutineScope: CoroutineScope,
    private val documentsBridge: CocoaRecentDocumentsBridge,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val stateLock = Any()
    private val pendingStartupProjects = LinkedHashMap<String, String>()
    private var pendingRecentPaths: List<String>? = null
    private var scheduledSyncAtNanos: Long = 0L
    private var workerJob: Job? = null
    private var syncedUris: List<URI> = emptyList()
    private var hasSynced: Boolean = false
    private val skippedSyncCount = AtomicInteger()
    private val appendSyncCount = AtomicInteger()
    private val replaceSyncCount = AtomicInteger()

    internal fun scheduleSync(recentPaths: List<String>, startupProjectPath: String? = null) {
        val recentPathsSnapshot = recentPaths.toList()
        val recentProjectKeys = recentPathsSnapshot.asSequence()
            .mapNotNull(::projectIdentityKeyOrNull)
            .toSet()
        synchronized(stateLock) {
            pendingRecentPaths = recentPathsSnapshot
            startupProjectPath?.let(::startupProjectPathToEntryOrNull)?.let { (projectKey, projectPath) ->
                pendingStartupProjects[projectKey] = projectPath
            }
            recentProjectKeys.forEach(pendingStartupProjects::remove)
            scheduledSyncAtNanos = System.nanoTime() + debounceMillis * NANOS_PER_MILLISECOND
            if (workerJob?.isActive != true) {
                workerJob = coroutineScope.launch {
                    processPendingRequests()
                }
            }
        }
    }

    internal suspend fun sync(targetUris: List<URI>) {
        val desiredUris = normalizeRecentUris(targetUris)
        if (hasSynced && desiredUris == syncedUris) {
            logSyncDecision(
                mode = "skip",
                modeCount = skippedSyncCount.incrementAndGet(),
                targetCount = desiredUris.size,
                deltaCount = 0,
            )
            return
        }

        if (canAppendIncrementally(syncedUris, desiredUris)) {
            val appendedUris = desiredUris.drop(syncedUris.size)
            documentsBridge.appendRecentDocuments(appendedUris)
            syncedUris = desiredUris
            hasSynced = true
            logSyncDecision(
                mode = "append",
                modeCount = appendSyncCount.incrementAndGet(),
                targetCount = desiredUris.size,
                deltaCount = appendedUris.size,
            )
        } else {
            documentsBridge.replaceRecentDocuments(desiredUris)
            syncedUris = desiredUris
            hasSynced = true
            logSyncDecision(
                mode = "replace",
                modeCount = replaceSyncCount.incrementAndGet(),
                targetCount = desiredUris.size,
                deltaCount = desiredUris.size,
            )
        }
    }

    private suspend fun processPendingRequests() {
        while (true) {
            when (val step = nextWorkerStep()) {
                is WorkerStep.Delay -> delay(step.delayMillis)
                is WorkerStep.Stop -> return
                is WorkerStep.Sync -> {
                    try {
                        syncPendingRequest(step.request)
                    } catch (throwable: Throwable) {
                        if (throwable is CancellationException) {
                            throw throwable
                        }
                        onFailure(throwable)
                    }
                }
            }
        }
    }

    private suspend fun syncPendingRequest(request: PendingSyncRequest) {
        val startupProjectsToRemove = withContext(Dispatchers.IO) {
            findStartupProjectsToRemove(
                recentPaths = request.recentPaths,
                startupProjects = request.startupProjects,
            )
        }
        if (startupProjectsToRemove.isNotEmpty()) {
            synchronized(stateLock) {
                startupProjectsToRemove.forEach(pendingStartupProjects::remove)
            }
        }

        val effectiveStartupProjects = request.startupProjects
            .filterKeys { it !in startupProjectsToRemove }
            .values
            .toSet()
        sync(collectTargetUris(request.recentPaths, effectiveStartupProjects))
    }

    private fun nextWorkerStep(): WorkerStep {
        return synchronized(stateLock) {
            val recentPaths = pendingRecentPaths
            if (recentPaths == null) {
                workerJob = null
                WorkerStep.Stop
            } else {
                val remainingNanos = scheduledSyncAtNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    WorkerStep.Delay((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
                } else {
                    pendingRecentPaths = null
                    WorkerStep.Sync(
                        PendingSyncRequest(
                            recentPaths = recentPaths,
                            startupProjects = LinkedHashMap(pendingStartupProjects),
                        ),
                    )
                }
            }
        }
    }

    private fun logSyncDecision(mode: String, modeCount: Int, targetCount: Int, deltaCount: Int) {
        if (!LOG.isDebugEnabled) {
            return
        }
        LOG.debug(
            "macOS recents sync decision=" +
                "$mode#$modeCount targetCount=$targetCount deltaCount=$deltaCount " +
                "counters(skip=${skippedSyncCount.get()}, append=${appendSyncCount.get()}, replace=${replaceSyncCount.get()})",
        )
    }

    private data class PendingSyncRequest(
        val recentPaths: List<String>,
        val startupProjects: Map<String, String>,
    )

    private sealed interface WorkerStep {
        data class Delay(
            val delayMillis: Long,
        ) : WorkerStep

        data class Sync(
            val request: PendingSyncRequest,
        ) : WorkerStep

        data object Stop : WorkerStep
    }

    private companion object {
        private const val DEFAULT_DEBOUNCE_MILLIS = 250L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private val LOG = Logger.getInstance(CocoaRecentProjectsCoordinator::class.java)
    }
}
