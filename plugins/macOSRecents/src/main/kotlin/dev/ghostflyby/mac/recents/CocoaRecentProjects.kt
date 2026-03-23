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

import com.intellij.ide.RecentProjectsManager
import com.intellij.ide.RecentProjectsManagerBase
import com.intellij.openapi.application.UI
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.ui.mac.foundation.Foundation
import kotlinx.coroutines.*
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.Path

internal class CocoaRecentProjectsListener : RecentProjectsManager.RecentProjectsChange {
    override fun change() {
        val recentPaths = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
        service<CocoaRecentProjectsSyncService>().scheduleSync(recentPaths = recentPaths)
    }
}

internal class StartUp : ProjectActivity {
    override suspend fun execute(project: Project) {
        val recentPaths = RecentProjectsManagerBase.getInstanceEx().getRecentPaths()
        service<CocoaRecentProjectsSyncService>().scheduleSync(
            recentPaths = recentPaths,
            startupProjectPath = project.projectFilePath,
        )
    }
}

@Service(Service.Level.APP)
internal class CocoaRecentProjectsSyncService(
    private val coroutineScope: CoroutineScope,
) {
    private val scheduler = CocoaRecentProjectsSyncScheduler(
        coroutineScope = coroutineScope,
        syncer = CocoaRecentProjectsSyncer(FoundationCocoaRecentDocumentsBridge()),
        onFailure = { throwable ->
            LOG.warn("Failed to update macOS recent documents.", throwable)
        },
    )

    internal fun scheduleSync(recentPaths: List<String>, startupProjectPath: String? = null) {
        scheduler.scheduleSync(recentPaths = recentPaths, startupProjectPath = startupProjectPath)
    }

    private companion object {
        private val LOG = Logger.getInstance(CocoaRecentProjectsSyncService::class.java)
    }
}

internal class CocoaRecentProjectsSyncScheduler(
    private val coroutineScope: CoroutineScope,
    private val syncer: CocoaRecentProjectsSyncer,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    private val onFailure: (Throwable) -> Unit = {},
) {
    private val stateLock = Any()
    private val pendingStartupProjects = LinkedHashMap<String, String>()
    private var pendingRecentPaths: List<String>? = null
    private var scheduledSyncAtNanos: Long = 0L
    private var workerJob: Job? = null

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

    private suspend fun processPendingRequests() {
        while (true) {
            when (val step = nextWorkerStep()) {
                is WorkerStep.Delay -> delay(step.delayMillis)
                is WorkerStep.Stop -> return
                is WorkerStep.Sync -> {
                    try {
                        syncer.sync(collectTargetUris(step.request.recentPaths, step.request.startupProjectPaths))
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

    private fun nextWorkerStep(): WorkerStep {
        return synchronized(stateLock) {
            val recentPaths = pendingRecentPaths
            if (recentPaths == null) {
                workerJob = null
                WorkerStep.Stop
            } else {
                val remainingNanos = scheduledSyncAtNanos - System.nanoTime()
                if (remainingNanos > 0L) {
                    WorkerStep.Delay(delayMillis = ((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND))
                } else {
                    pendingRecentPaths = null
                    WorkerStep.Sync(
                        PendingSyncRequest(
                            recentPaths = recentPaths,
                            startupProjectPaths = pendingStartupProjects.values.toSet(),
                        ),
                    )
                }
            }
        }
    }

    private data class PendingSyncRequest(
        val recentPaths: List<String>,
        val startupProjectPaths: Set<String>,
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
    }
}

internal class CocoaRecentProjectsSyncer(
    private val documentsBridge: CocoaRecentDocumentsBridge,
) {
    private var syncedUris: List<URI> = emptyList()
    private val skippedSyncCount = AtomicInteger()
    private val appendSyncCount = AtomicInteger()
    private val replaceSyncCount = AtomicInteger()

    internal suspend fun sync(targetUris: List<URI>) {
        val desiredUris = normalizeRecentUris(targetUris)
        if (desiredUris == syncedUris) {
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
            logSyncDecision(
                mode = "append",
                modeCount = appendSyncCount.incrementAndGet(),
                targetCount = desiredUris.size,
                deltaCount = appendedUris.size,
            )
        } else {
            documentsBridge.replaceRecentDocuments(desiredUris)
            syncedUris = desiredUris
            logSyncDecision(
                mode = "replace",
                modeCount = replaceSyncCount.incrementAndGet(),
                targetCount = desiredUris.size,
                deltaCount = desiredUris.size,
            )
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

    private companion object {
        private val LOG = Logger.getInstance(CocoaRecentProjectsSyncer::class.java)
    }
}

internal interface CocoaRecentDocumentsBridge {
    suspend fun appendRecentDocuments(uris: List<URI>)
    suspend fun replaceRecentDocuments(uris: List<URI>)
}

internal class FoundationCocoaRecentDocumentsBridge : CocoaRecentDocumentsBridge {
    override suspend fun appendRecentDocuments(uris: List<URI>) {
        updateRecentDocuments(uris = uris, clearExisting = false)
    }

    override suspend fun replaceRecentDocuments(uris: List<URI>) {
        updateRecentDocuments(uris = uris, clearExisting = true)
    }

    private suspend fun updateRecentDocuments(uris: List<URI>, clearExisting: Boolean) {
        if (uris.isEmpty() && !clearExisting) {
            return
        }

        withContext(Dispatchers.UI) {
            val controller = Foundation.invoke(documentControllerClass, "sharedDocumentController")
            if (clearExisting) {
                Foundation.invoke(controller, "clearRecentDocuments:", null)
            }
            uris.forEach { uri ->
                val nsUrl = Foundation.invoke(nsUrlClass, "URLWithString:", Foundation.nsString(uri.toString()))
                Foundation.invoke(controller, "noteNewRecentDocumentURL:", nsUrl)
            }
        }
    }

    private companion object {
        private val documentControllerClass = Foundation.getObjcClass("NSDocumentController")
        private val nsUrlClass = Foundation.getObjcClass("NSURL")
    }
}

internal fun collectTargetUris(
    recentPaths: List<String>,
    startupProjectPaths: Set<String> = emptySet(),
): List<URI> {
    val recentUris = recentPaths.mapNotNull(::pathToUriOrNull)
    val startupUris = startupProjectPaths
        .asSequence()
        .mapNotNull(::pathToUriOrNull)
        .filterNot(recentUris::contains)
        .toList()
    if (startupUris.isEmpty()) {
        return recentUris
    }
    return recentUris + startupUris
}

private fun pathToUriOrNull(path: String): URI? {
    return runCatching { Path(path).toUri() }.getOrNull()
}

private fun startupProjectPathToEntryOrNull(path: String): Pair<String, String>? {
    val projectKey = projectIdentityKeyOrNull(path) ?: return null
    return projectKey to path
}

private fun projectIdentityKeyOrNull(path: String): String? {
    val rawPath = runCatching { Path(path) }.getOrNull() ?: return null
    return normalizeProjectIdentityPath(rawPath).toSystemIndependentPath()
}

private fun normalizeProjectIdentityPath(path: java.nio.file.Path): java.nio.file.Path {
    val absolutePath = path.toAbsolutePath().normalize()
    val projectRootPath = absolutePath.toDirectoryBasedProjectRootOrSelf()
    return runCatching { projectRootPath.toRealPath() }.getOrElse { projectRootPath }
}

private fun java.nio.file.Path.toDirectoryBasedProjectRootOrSelf(): java.nio.file.Path {
    if (fileName?.toString() != DIRECTORY_BASED_PROJECT_FILE_NAME) {
        return this
    }
    val ideaDirectory = parent ?: return this
    if (ideaDirectory.fileName?.toString() != IDEA_DIRECTORY_NAME) {
        return this
    }
    return ideaDirectory.parent ?: this
}

private fun java.nio.file.Path.toSystemIndependentPath(): String {
    return toString().replace('\\', '/')
}

private fun normalizeRecentUris(rawUris: List<URI>): List<URI> {
    val deduplicatedUris = LinkedHashSet<URI>()
    rawUris.forEach(deduplicatedUris::add)
    return deduplicatedUris.toList().asReversed()
}

private fun canAppendIncrementally(currentUris: List<URI>, desiredUris: List<URI>): Boolean {
    if (currentUris.isEmpty()) {
        return false
    }
    if (desiredUris.size < currentUris.size) {
        return false
    }
    return desiredUris.subList(0, currentUris.size) == currentUris
}

private const val DIRECTORY_BASED_PROJECT_FILE_NAME = "misc.xml"
private const val IDEA_DIRECTORY_NAME = ".idea"
