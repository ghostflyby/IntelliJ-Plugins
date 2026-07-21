/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ghostflyby.spotless.api.SpotlessDaemonLifecycle
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import dev.ghostflyby.spotless.api.SpotlessDaemonStartContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

internal enum class SpotlessDaemonRuntimeState {
    Starting,
    Running,
}

internal data class SpotlessDaemonRuntimeEntry(
    val provider: SpotlessProviderHandle,
    val externalProject: Path,
    val state: SpotlessDaemonRuntimeState,
)

internal data class SpotlessDaemonRuntimeSnapshot(
    val entries: List<SpotlessDaemonRuntimeEntry> = emptyList(),
)

internal class SpotlessDaemonRegistry(
    private val project: Project,
    private val scope: CoroutineScope,
    private val clientProvider: () -> SpotlessDaemonClient,
) {
    private val capabilityCache = project.service<SpotlessCapabilityCache>()
    private val logger = logger<SpotlessDaemonRegistry>()

    private data class RegistryKey(
        val externalProjectPath: String,
    )

    private sealed interface DaemonEntry {
        val provider: SpotlessProviderHandle
        val attachment: SpotlessDaemonAttachment
    }

    private data class StartingDaemonEntry(
        override val provider: SpotlessProviderHandle,
        override val attachment: SpotlessDaemonAttachment,
        val result: CompletableDeferred<SpotlessDaemonProvider.Endpoint>,
        val startupJob: Job,
    ) : DaemonEntry

    private data class RunningDaemonEntry(
        override val provider: SpotlessProviderHandle,
        override val attachment: SpotlessDaemonAttachment,
        val endpoint: SpotlessDaemonProvider.Endpoint,
    ) : DaemonEntry

    private data class DetachedDaemonEntry(
        val entry: DaemonEntry,
        val cleanups: List<() -> Unit>,
        val stopHttp: Boolean,
    )

    private sealed interface DaemonResolution {
        data class Start(val entry: StartingDaemonEntry) : DaemonResolution
        data class Await(val result: Deferred<SpotlessDaemonProvider.Endpoint>) : DaemonResolution
        data class Return(val endpoint: SpotlessDaemonProvider.Endpoint) : DaemonResolution
        data class Replace(val detached: DetachedDaemonEntry) : DaemonResolution
    }

    /* The concurrent map makes cheap visibility checks safe; all mutations use entriesMutex. */
    private val entries = ConcurrentHashMap<RegistryKey, DaemonEntry>()
    private val entriesMutex = Mutex()
    private val mutableRuntimeState = MutableStateFlow(SpotlessDaemonRuntimeSnapshot())

    val runtimeState: StateFlow<SpotlessDaemonRuntimeSnapshot> = mutableRuntimeState.asStateFlow()

    suspend fun getDaemon(
        provider: SpotlessProviderHandle,
        externalProject: Path,
    ): SpotlessDaemonProvider.Endpoint {
        currentCoroutineContext().ensureActive()
        val key = key(externalProject)
        val requesterJob = currentCoroutineContext().job
        while (true) {
            when (val resolution = entriesMutex.withLock {
                if (!provider.isAttached) {
                    throw CancellationException("Spotless provider was detached")
                }
                when (val current = entries[key]) {
                    null -> {
                        val attachment = SpotlessDaemonAttachment(key)
                        val entry = StartingDaemonEntry(
                            provider,
                            attachment,
                            CompletableDeferred(),
                            requesterJob,
                        )
                        entries[key] = entry
                        DaemonResolution.Start(entry)
                    }

                    is StartingDaemonEntry -> {
                        if (current.provider === provider) {
                            DaemonResolution.Await(current.result)
                        } else {
                            DaemonResolution.Replace(detachLocked(key, current, "provider switched", stopHttp = true))
                        }
                    }

                    is RunningDaemonEntry -> {
                        if (current.provider === provider) {
                            DaemonResolution.Return(current.endpoint)
                        } else {
                            DaemonResolution.Replace(detachLocked(key, current, "provider switched", stopHttp = true))
                        }
                    }
                }
            }) {
                is DaemonResolution.Await -> return resolution.result.await()
                is DaemonResolution.Return -> return resolution.endpoint
                is DaemonResolution.Start -> {
                    publishRuntimeState()
                    return startDaemon(key, resolution.entry, externalProject)
                }

                is DaemonResolution.Replace -> {
                    publishRuntimeState()
                    closeDetached(resolution.detached)
                }
            }
        }
    }

    suspend fun releaseAllDaemons(): Int {
        val detached = detachEntries({ true }, "project release")
        detached.forEach(::scheduleClose)
        return detached.size
    }

    suspend fun releaseDaemonsForProvider(provider: SpotlessProviderHandle): Int {
        val detached = detachEntries(
            predicate = { it.provider === provider },
            reason = "provider release",
        )
        detached.forEach(::scheduleClose)
        return detached.size
    }

    suspend fun restartDaemonsForProvider(
        provider: SpotlessProviderHandle,
        externalProjects: Collection<Path>,
    ): Int {
        val restartKeys = externalProjects.map(::key).toSet()
        val detached = detachEntries(
            predicate = { it.provider === provider },
            reason = "provider state changed",
        )
        for (entry in detached) {
            closeDetached(entry)
        }
        detached.asSequence()
            .map { it.entry.attachment.key }
            .filter(restartKeys::contains)
            .distinct()
            .forEach { restartKey ->
                val externalProject = Path.of(restartKey.externalProjectPath)
                try {
                    getDaemon(provider, externalProject)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    logger.warn(
                        "Failed to restart Spotless daemon after provider state change: $externalProject",
                        error,
                    )
                }
            }
        return detached.size
    }

    suspend fun releaseDaemon(
        provider: SpotlessProviderHandle,
        externalProject: Path,
    ): Boolean {
        val targetKey = key(externalProject)
        val detached = detachEntries(
            predicate = { entry ->
                entry.provider === provider && entry.attachment.key == targetKey
            },
            reason = "external project release",
        )
        detached.forEach { entry -> closeDetached(entry) }
        return detached.isNotEmpty()
    }

    fun releaseDaemonsForProviderSynchronously(provider: SpotlessProviderHandle): Int =
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                val detached = entriesMutex.withLock {
                    detachEntriesLocked(
                        predicate = { it.provider === provider },
                        reason = "provider extension removed",
                    )
                }
                if (detached.isNotEmpty()) {
                    publishRuntimeState()
                }
                detached.map { entry ->
                    async { closeDetached(entry, providerRemovalStopTimeout) }
                }.awaitAll()
                detached.size
            }
        }

    fun hasRunningDaemons(): Boolean = entries.isNotEmpty()

    fun dispose() {
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                val detached = detachEntries({ true }, "service disposed")
                capabilityCache.clear()
                detached.map { entry ->
                    async { closeDetached(entry) }
                }.awaitAll()
            }
        }
    }

    private suspend fun startDaemon(
        key: RegistryKey,
        entry: StartingDaemonEntry,
        externalProject: Path,
    ): SpotlessDaemonProvider.Endpoint {
        val endpoint = try {
            entry.provider.provider.startDaemon(
                DaemonStartContext(project, externalProject, entry.attachment),
            )
        } catch (error: Throwable) {
            val detached = withContext(NonCancellable) {
                entriesMutex.withLock {
                    if (entries[key] === entry) {
                        detachLocked(key, entry, "daemon start failed", false)
                    } else {
                        null
                    }
                }
            }
            detached?.let {
                publishRuntimeState()
                closeDetached(detached)
            }
            entry.result.completeExceptionally(error)
            throw error
        }

        val published = withContext(NonCancellable) {
            entriesMutex.withLock {
                if (entries[key] === entry && entry.attachment.isOpen()) {
                    entries[key] = RunningDaemonEntry(entry.provider, entry.attachment, endpoint)
                    entry.result.complete(endpoint)
                    true
                } else {
                    entry.result.completeExceptionally(
                        CancellationException("Spotless daemon was detached while starting"),
                    )
                    false
                }
            }
        }
        if (published) {
            publishRuntimeState()
            return endpoint
        }

        if (entry.attachment.shouldStopReturnedEndpoint()) {
            withContext(NonCancellable) {
                stopEndpoint(endpoint, "daemon detached while starting", null)
            }
        }
        throw CancellationException("Spotless daemon was detached while starting")
    }

    private suspend fun detachEntries(
        predicate: (DaemonEntry) -> Boolean,
        reason: String,
    ): List<DetachedDaemonEntry> {
        val detached = entriesMutex.withLock {
            detachEntriesLocked(predicate, reason)
        }
        if (detached.isNotEmpty()) {
            publishRuntimeState()
        }
        return detached
    }

    private fun detachEntriesLocked(
        predicate: (DaemonEntry) -> Boolean,
        reason: String,
    ): List<DetachedDaemonEntry> = entries.entries
        .filter { predicate(it.value) }
        .mapNotNull { (key, entry) ->
            if (entries[key] === entry) detachLocked(key, entry, reason, true) else null
        }

    private fun detachLocked(
        key: RegistryKey,
        entry: DaemonEntry,
        reason: String,
        stopHttp: Boolean,
    ): DetachedDaemonEntry {
        check(entries.remove(key, entry))
        capabilityCache.invalidateExternalProject(key.externalProjectPath)
        val cleanups = entry.attachment.beginClose(reason, stopHttp)
        if (entry is StartingDaemonEntry) {
            val cancellation = CancellationException("Spotless daemon detached: $reason")
            entry.startupJob.cancel(cancellation)
            entry.result.completeExceptionally(cancellation)
        }
        return DetachedDaemonEntry(entry, cleanups, stopHttp)
    }

    private fun scheduleClose(entry: DetachedDaemonEntry) {
        scope.launch(Dispatchers.IO) {
            closeDetached(entry)
        }
    }

    private suspend fun closeDetached(
        detached: DetachedDaemonEntry,
        stopTimeout: Duration? = null,
    ) {
        val callerJob = currentCoroutineContext().job
        withContext(NonCancellable) {
            val endpoint = (detached.entry as? RunningDaemonEntry)?.endpoint
            if (detached.stopHttp && endpoint != null) {
                stopEndpoint(endpoint, detached.entry.attachment.closeReason(), stopTimeout)
            }
            detached.entry.attachment.runCleanups(detached.cleanups)
            val startupJob = (detached.entry as? StartingDaemonEntry)?.startupJob
            if (startupJob != null && startupJob !== callerJob) {
                startupJob.join()
            }
        }
    }

    private suspend fun stopEndpoint(
        endpoint: SpotlessDaemonProvider.Endpoint,
        reason: String,
        timeout: Duration?,
    ) {
        val stopped = runCatching {
            if (timeout == null) {
                clientProvider().stop(endpoint)
                true
            } else {
                withTimeoutOrNull(timeout) {
                    clientProvider().stop(endpoint)
                    true
                } ?: false
            }
        }
        stopped.exceptionOrNull()?.let { error ->
            logger.warn("Failed to stop daemon ($reason): $endpoint", error)
        }
        if (stopped.getOrNull() == false) {
            logger.warn("Timed out stopping daemon ($reason): $endpoint")
        }
    }

    private fun key(externalProject: Path): RegistryKey =
        RegistryKey(externalProject.normalize().absolutePathString())

    private inner class SpotlessDaemonAttachment(
        val key: RegistryKey,
    ) : SpotlessDaemonLifecycle {
        private val cleanupLock = Any()
        private val cleanups = ArrayDeque<() -> Unit>()
        private var open = true
        private var closeReason = "daemon detached"
        private var stopReturnedEndpoint = false

        override fun requestClose(reason: String) {
            scope.launch(Dispatchers.IO) {
                val detached = entriesMutex.withLock {
                    val entry = entries[key]
                    if (entry?.attachment === this@SpotlessDaemonAttachment) {
                        detachLocked(key, entry, reason, stopHttp = false)
                    } else {
                        null
                    }
                } ?: return@launch
                publishRuntimeState()
                closeDetached(detached)
            }
        }

        override fun registerCleanup(cleanup: () -> Unit) {
            val runImmediately = synchronized(cleanupLock) {
                if (open) {
                    cleanups.addFirst(cleanup)
                    null
                } else {
                    cleanup
                }
            }
            runImmediately?.let(::runCleanup)
        }

        fun beginClose(reason: String, stopHttp: Boolean): List<() -> Unit> = synchronized(cleanupLock) {
            if (!open) {
                emptyList()
            } else {
                open = false
                closeReason = reason
                stopReturnedEndpoint = stopHttp
                val snapshot = cleanups.toList()
                cleanups.clear()
                snapshot
            }
        }

        fun isOpen(): Boolean = synchronized(cleanupLock) { open }

        fun shouldStopReturnedEndpoint(): Boolean = synchronized(cleanupLock) { stopReturnedEndpoint }

        fun closeReason(): String = synchronized(cleanupLock) { closeReason }

        fun runCleanups(cleanupSnapshot: List<() -> Unit>) {
            cleanupSnapshot.forEach(::runCleanup)
        }

        private fun runCleanup(cleanup: () -> Unit) {
            runCatching(cleanup).onFailure { error ->
                logger.warn("Provider daemon cleanup failed (${closeReason()}): $key", error)
            }
        }
    }

    private companion object {
        val providerRemovalStopTimeout: Duration = 2.seconds
    }

    private fun publishRuntimeState() {
        mutableRuntimeState.value = SpotlessDaemonRuntimeSnapshot(
            entries.entries
                .map { (key, entry) ->
                    SpotlessDaemonRuntimeEntry(
                        provider = entry.provider,
                        externalProject = Path.of(key.externalProjectPath),
                        state = when (entry) {
                            is StartingDaemonEntry -> SpotlessDaemonRuntimeState.Starting
                            is RunningDaemonEntry -> SpotlessDaemonRuntimeState.Running
                        },
                    )
                }
                .sortedBy { entry -> entry.externalProject.toString() },
        )
    }

    private data class DaemonStartContext(
        override val project: Project,
        override val externalProjectRoot: Path,
        override val lifecycle: SpotlessDaemonLifecycle,
    ) : SpotlessDaemonStartContext
}
