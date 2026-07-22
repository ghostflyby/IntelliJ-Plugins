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
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal enum class SpotlessDaemonRuntimeState {
    Starting,
    Ready,
}

internal data class SpotlessDaemonRuntimeEntry(
    val session: ProviderSession,
    val externalProject: Path,
    val state: SpotlessDaemonRuntimeState,
)

internal data class SpotlessDaemonRuntimeSnapshot(
    val entries: List<SpotlessDaemonRuntimeEntry> = emptyList(),
)

internal class SpotlessDaemonRegistry(
    private val project: Project,
    projectScope: CoroutineScope,
    private val clientProvider: () -> SpotlessDaemonClient,
    private val daemonCleanupTimeout: Duration = defaultDaemonCleanupTimeout,
    private val providerRemovalCleanupTimeout: Duration = defaultProviderRemovalCleanupTimeout,
) {
    private val capabilityCache = project.service<SpotlessCapabilityCache>()
    private val logger = logger<SpotlessDaemonRegistry>()
    private val registryJob = SupervisorJob(projectScope.coroutineContext[Job])
    private val registryScope = CoroutineScope(projectScope.coroutineContext + registryJob)
    private val cleanupJob = SupervisorJob()
    private val cleanupScope = CoroutineScope(Dispatchers.IO + cleanupJob)

    private data class DaemonKey(
        val session: ProviderSession,
        val root: Path,
    )

    private sealed interface DaemonEntry {
        val generation: Long
        val lifecycle: DaemonLifecycle
    }

    private data class Starting(
        override val generation: Long,
        override val lifecycle: DaemonLifecycle,
        val task: Deferred<SpotlessDaemonProvider.Endpoint>,
    ) : DaemonEntry

    private data class Ready(
        override val generation: Long,
        override val lifecycle: DaemonLifecycle,
        val endpoint: SpotlessDaemonProvider.Endpoint,
    ) : DaemonEntry

    private data class ReadyLease(
        val key: DaemonKey,
        val entry: Ready,
    )

    private data class DetachedDaemon(
        val key: DaemonKey,
        val entry: DaemonEntry,
        val cleanups: List<() -> Unit>,
        val stopHttp: Boolean,
    )

    private data class CleanupDeadline(
        val started: TimeMark,
        val timeout: Duration,
    ) {
        fun remaining(): Duration = timeout - started.elapsedNow()
    }

    private val entries = linkedMapOf<DaemonKey, DaemonEntry>()
    private val entriesMutex = Mutex()
    private val mutableRuntimeState = MutableStateFlow(SpotlessDaemonRuntimeSnapshot())

    val runtimeState: StateFlow<SpotlessDaemonRuntimeSnapshot> = mutableRuntimeState.asStateFlow()

    suspend fun <T> withDaemon(
        session: ProviderSession,
        externalProject: Path,
        generation: Long,
        operation: suspend (SpotlessDaemonProvider.Endpoint) -> T,
    ): T {
        val ready = getDaemon(session, externalProject, generation)
        try {
            return operation(ready.entry.endpoint)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SpotlessDaemonTransportException) {
            releaseIfCurrent(ready, "daemon transport failed")
            throw error
        }
    }

    suspend fun releaseAllDaemons(): Int =
        releaseMatchingDaemons({ true }, "project release", daemonCleanupTimeout)

    suspend fun releaseDaemonsForSession(session: ProviderSession): Int =
        releaseMatchingDaemons(
            predicate = { (key) -> key.session === session },
            reason = "provider release",
            timeout = daemonCleanupTimeout,
        )

    suspend fun reconcile(
        session: ProviderSession,
        previous: ProviderSnapshot,
        current: ProviderSnapshot,
    ): Int {
        val removedRoots = previous.generations.keys - current.generations.keys
        val changedRoots = previous.generations.keys
            .intersect(current.generations.keys)
            .filterTo(linkedSetOf()) { root -> previous.generations[root] != current.generations[root] }
        val affectedRoots = removedRoots + changedRoots
        if (affectedRoots.isEmpty()) {
            return 0
        }

        val deadline = cleanupDeadline(daemonCleanupTimeout)
        val detached = detachEntries(
            predicate = { (key, entry) ->
                key.session === session &&
                        key.root in affectedRoots &&
                        current.generations[key.root] != entry.generation
            },
            reason = "provider state changed",
            deadline = deadline,
        )
        val restartRoots = detached.asSequence()
            .map(DetachedDaemon::key)
            .map(DaemonKey::root)
            .filter(changedRoots::contains)
            .distinct()
            .toList()
        closeAll(detached, deadline)

        restartRoots.forEach { root ->
            val generation = current.generations[root] ?: return@forEach
            try {
                getDaemon(session, root, generation)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("Failed to restart Spotless daemon after provider state change: $root", error)
            }
        }
        return detached.size
    }

    suspend fun releaseDaemon(
        session: ProviderSession,
        externalProject: Path,
    ): Boolean {
        val key = key(session, externalProject)
        val deadline = cleanupDeadline(daemonCleanupTimeout)
        val detached = detachEntries(
            predicate = { (entryKey) -> entryKey == key },
            reason = "external project release",
            deadline = deadline,
        )
        closeAll(detached, deadline)
        return detached.isNotEmpty()
    }

    suspend fun restartDaemon(
        session: ProviderSession,
        externalProject: Path,
        generation: Long,
    ) {
        releaseDaemon(session, externalProject)
        getDaemon(session, externalProject, generation)
    }

    fun releaseSessionSynchronously(session: ProviderSession): Int =
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                releaseMatchingDaemons(
                    predicate = { (key) -> key.session === session },
                    reason = "provider extension removed",
                    timeout = providerRemovalCleanupTimeout,
                )
            }
        }

    fun hasRunningDaemons(): Boolean = runtimeState.value.entries.isNotEmpty()

    fun dispose() {
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                releaseMatchingDaemons({ true }, "service disposed", daemonCleanupTimeout)
                capabilityCache.clear()
            }
        }
        registryJob.cancel()
        cleanupJob.complete()
    }

    private suspend fun getDaemon(
        session: ProviderSession,
        externalProject: Path,
        generation: Long,
    ): ReadyLease {
        currentCoroutineContext().ensureActive()
        val normalizedRoot = externalProject.toAbsolutePath().normalize()
        val key = key(session, normalizedRoot)

        while (true) {
            var stale: DetachedDaemon? = null
            var staleDeadline: CleanupDeadline? = null
            val entry = entriesMutex.withLock {
                ensureCurrentProviderState(session, normalizedRoot, generation)
                val current = entries[key]
                if (current != null && current.generation != generation) {
                    val deadline = cleanupDeadline(daemonCleanupTimeout)
                    staleDeadline = deadline
                    stale = detachLocked(
                        key,
                        current,
                        "provider generation changed",
                        stopHttp = true,
                        deadline = deadline,
                    )
                    publishRuntimeStateLocked()
                    null
                } else {
                    current ?: createStartingLocked(key, generation)
                }
            }

            stale?.let { detached ->
                closeAll(listOf(detached), requireNotNull(staleDeadline))
                continue
            }

            when (val current = requireNotNull(entry)) {
                is Ready -> return ReadyLease(key, current)
                is Starting -> {
                    current.task.start()
                    current.task.await()
                }
            }
        }
    }

    private fun createStartingLocked(
        key: DaemonKey,
        generation: Long,
    ): Starting {
        val lifecycle = DaemonLifecycle(key)
        val task = registryScope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            startDaemon(key, generation, lifecycle)
        }
        return Starting(generation, lifecycle, task).also { entry ->
            entries[key] = entry
            publishRuntimeStateLocked()
        }
    }

    private suspend fun startDaemon(
        key: DaemonKey,
        generation: Long,
        lifecycle: DaemonLifecycle,
    ): SpotlessDaemonProvider.Endpoint {
        val startupTask = currentCoroutineContext().job
        var endpoint: SpotlessDaemonProvider.Endpoint? = null
        try {
            endpoint = key.session.provider.startDaemon(
                DaemonStartContext(project, key.root, lifecycle),
            )
            clientProvider().awaitReady(endpoint, daemonStartupTimeout)
            val published = withContext(NonCancellable) {
                entriesMutex.withLock {
                    val current = entries[key]
                    if (
                        current is Starting &&
                        current.task === startupTask &&
                        current.generation == generation &&
                        lifecycle.isOpen() &&
                        key.session.isAttached &&
                        key.session.snapshot.generations[key.root] == generation
                    ) {
                        entries[key] = Ready(generation, lifecycle, endpoint)
                        publishRuntimeStateLocked()
                        true
                    } else {
                        false
                    }
                }
            }
            if (published) {
                return endpoint
            }
            if (lifecycle.shouldStopReturnedEndpoint()) {
                stopEndpoint(
                    endpoint,
                    "daemon detached while starting",
                    lifecycle.remainingCloseTime(daemonCleanupTimeout),
                )
            }
            throw CancellationException("Spotless daemon was detached while starting")
        } catch (error: Throwable) {
            val detached = withContext(NonCancellable) {
                entriesMutex.withLock {
                    val current = entries[key]
                    if (current is Starting && current.task === startupTask) {
                        val deadline = cleanupDeadline(daemonCleanupTimeout)
                        detachLocked(
                            key,
                            current,
                            "daemon start failed",
                            stopHttp = false,
                            deadline = deadline,
                            cancelStarting = false,
                        ).also { publishRuntimeStateLocked() }
                    } else {
                        null
                    }
                }
            }
            if (detached != null) {
                closeDetached(
                    detached = detached,
                    deadline = detached.entry.lifecycle.closeDeadline(),
                    waitForStartup = false,
                )
            } else if (endpoint != null && lifecycle.shouldStopReturnedEndpoint()) {
                stopEndpoint(
                    endpoint,
                    "daemon detached while starting",
                    lifecycle.remainingCloseTime(daemonCleanupTimeout),
                )
            }
            throw error
        }
    }

    private fun ensureCurrentProviderState(
        session: ProviderSession,
        externalProject: Path,
        generation: Long,
    ) {
        if (!session.isAttached) {
            throw CancellationException("Spotless provider was detached")
        }
        if (session.snapshot.generations[externalProject] != generation) {
            throw CancellationException("Spotless provider state changed")
        }
    }

    private suspend fun releaseIfCurrent(
        ready: ReadyLease,
        reason: String,
    ) {
        val deadline = cleanupDeadline(daemonCleanupTimeout)
        val detached = entriesMutex.withLock {
            if (entries[ready.key] === ready.entry) {
                detachLocked(
                    ready.key,
                    ready.entry,
                    reason,
                    stopHttp = true,
                    deadline = deadline,
                )
                    .also { publishRuntimeStateLocked() }
            } else {
                null
            }
        } ?: return
        closeAll(listOf(detached), deadline)
    }

    private suspend fun releaseMatchingDaemons(
        predicate: (Map.Entry<DaemonKey, DaemonEntry>) -> Boolean,
        reason: String,
        timeout: Duration,
    ): Int {
        val deadline = cleanupDeadline(timeout)
        val detached = detachEntries(predicate, reason, deadline)
        closeAll(detached, deadline)
        return detached.size
    }

    private suspend fun detachEntries(
        predicate: (Map.Entry<DaemonKey, DaemonEntry>) -> Boolean,
        reason: String,
        deadline: CleanupDeadline,
    ): List<DetachedDaemon> = entriesMutex.withLock {
        entries.entries
            .filter(predicate)
            .map { (key, entry) ->
                detachLocked(key, entry, reason, stopHttp = true, deadline = deadline)
            }
            .also { detached ->
                if (detached.isNotEmpty()) {
                    publishRuntimeStateLocked()
                }
            }
    }

    private fun detachLocked(
        key: DaemonKey,
        entry: DaemonEntry,
        reason: String,
        stopHttp: Boolean,
        deadline: CleanupDeadline,
        cancelStarting: Boolean = true,
    ): DetachedDaemon {
        check(entries.remove(key, entry))
        capabilityCache.invalidateExternalProject(key.root.absolutePathString())
        val cleanups = entry.lifecycle.beginClose(reason, stopHttp, deadline)
        if (cancelStarting && entry is Starting) {
            entry.task.cancel(CancellationException("Spotless daemon detached: $reason"))
        }
        return DetachedDaemon(key, entry, cleanups, stopHttp)
    }

    private suspend fun closeAll(
        detached: List<DetachedDaemon>,
        deadline: CleanupDeadline,
    ) {
        if (detached.isEmpty()) {
            return
        }
        withContext(NonCancellable + Dispatchers.IO) {
            detached.map { daemon ->
                async { closeDetached(daemon, deadline, waitForStartup = true) }
            }.awaitAll()
        }
    }

    private suspend fun closeDetached(
        detached: DetachedDaemon,
        deadline: CleanupDeadline,
        waitForStartup: Boolean,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            val endpoint = (detached.entry as? Ready)?.endpoint
            if (detached.stopHttp && endpoint != null) {
                stopEndpoint(endpoint, detached.entry.lifecycle.closeReason(), deadline.remaining())
            }
            runCleanups(detached, deadline)
            if (waitForStartup && detached.entry is Starting) {
                joinStartup(detached.entry.task, detached.key, deadline.remaining())
            }
            if (deadline.remaining() <= Duration.ZERO) {
                logger.warn("Timed out cleaning up Spotless daemon: ${detached.key}")
            }
        }
    }

    private suspend fun runCleanups(
        detached: DetachedDaemon,
        deadline: CleanupDeadline,
    ) {
        if (detached.cleanups.isEmpty()) {
            return
        }
        val cleanupTask = cleanupScope.launch {
            detached.entry.lifecycle.runCleanups(detached.cleanups)
        }
        val remaining = deadline.remaining()
        val completed = remaining > Duration.ZERO && withTimeoutOrNull(remaining) {
            cleanupTask.join()
            true
        } == true
        if (!completed) {
            logger.warn("Timed out waiting for provider daemon cleanup: ${detached.key}")
        }
    }

    private suspend fun joinStartup(
        task: Deferred<SpotlessDaemonProvider.Endpoint>,
        key: DaemonKey,
        timeout: Duration,
    ) {
        if (timeout <= Duration.ZERO) {
            logger.warn("Timed out waiting for Spotless daemon startup cancellation: $key")
            return
        }
        val completed = withTimeoutOrNull(timeout) {
            task.join()
            true
        } ?: false
        if (!completed) {
            logger.warn("Timed out waiting for Spotless daemon startup cancellation: $key")
        }
    }

    private suspend fun stopEndpoint(
        endpoint: SpotlessDaemonProvider.Endpoint,
        reason: String,
        timeout: Duration,
    ) {
        if (timeout <= Duration.ZERO) {
            logger.warn("Timed out stopping daemon ($reason): $endpoint")
            return
        }
        val stopped = runCatching {
            withTimeoutOrNull(timeout) {
                clientProvider().stop(endpoint)
                true
            } ?: false
        }
        stopped.exceptionOrNull()?.let { error ->
            logger.warn("Failed to stop daemon ($reason): $endpoint", error)
        }
        if (stopped.getOrNull() == false) {
            logger.warn("Timed out stopping daemon ($reason): $endpoint")
        }
    }

    private fun key(
        session: ProviderSession,
        externalProject: Path,
    ): DaemonKey = DaemonKey(session, externalProject.toAbsolutePath().normalize())

    private fun cleanupDeadline(timeout: Duration): CleanupDeadline =
        CleanupDeadline(TimeSource.Monotonic.markNow(), timeout)

    private inner class DaemonLifecycle(
        val key: DaemonKey,
    ) : SpotlessDaemonLifecycle {
        private val cleanupLock = Any()
        private val cleanups = ArrayDeque<() -> Unit>()
        private var open = true
        private var closeReason = "daemon detached"
        private var stopReturnedEndpoint = false
        private var deadline: CleanupDeadline? = null

        override fun requestClose(reason: String) {
            registryScope.launch(Dispatchers.IO) {
                val deadline = cleanupDeadline(daemonCleanupTimeout)
                val detached = entriesMutex.withLock {
                    val entry = entries[key]
                    if (entry?.lifecycle === this@DaemonLifecycle) {
                        detachLocked(
                            key,
                            entry,
                            reason,
                            stopHttp = false,
                            deadline = deadline,
                        )
                            .also { publishRuntimeStateLocked() }
                    } else {
                        null
                    }
                } ?: return@launch
                closeAll(listOf(detached), deadline)
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

        fun beginClose(
            reason: String,
            stopHttp: Boolean,
            deadline: CleanupDeadline,
        ): List<() -> Unit> = synchronized(cleanupLock) {
            if (!open) {
                emptyList()
            } else {
                open = false
                closeReason = reason
                stopReturnedEndpoint = stopHttp
                this.deadline = deadline
                val snapshot = cleanups.toList()
                cleanups.clear()
                snapshot
            }
        }

        fun isOpen(): Boolean = synchronized(cleanupLock) { open }

        fun shouldStopReturnedEndpoint(): Boolean = synchronized(cleanupLock) { stopReturnedEndpoint }

        fun closeReason(): String = synchronized(cleanupLock) { closeReason }

        fun closeDeadline(): CleanupDeadline = synchronized(cleanupLock) {
            requireNotNull(deadline)
        }

        fun remainingCloseTime(fallback: Duration): Duration = synchronized(cleanupLock) {
            deadline?.remaining() ?: fallback
        }

        fun runCleanups(cleanupSnapshot: List<() -> Unit>) {
            cleanupSnapshot.forEach(::runCleanup)
        }

        private fun runCleanup(cleanup: () -> Unit) {
            runCatching(cleanup).onFailure { error ->
                logger.warn("Provider daemon cleanup failed (${closeReason()}): $key", error)
            }
        }
    }

    private fun publishRuntimeStateLocked() {
        mutableRuntimeState.value = SpotlessDaemonRuntimeSnapshot(
            entries.map { (key, entry) ->
                SpotlessDaemonRuntimeEntry(
                    session = key.session,
                    externalProject = key.root,
                    state = when (entry) {
                        is Starting -> SpotlessDaemonRuntimeState.Starting
                        is Ready -> SpotlessDaemonRuntimeState.Ready
                    },
                )
            }.sortedWith(
                compareBy<SpotlessDaemonRuntimeEntry> { it.externalProject.toString() }
                    .thenBy { it.session.provider.javaClass.name },
            ),
        )
    }

    private data class DaemonStartContext(
        override val project: Project,
        override val externalProjectRoot: Path,
        override val lifecycle: SpotlessDaemonLifecycle,
    ) : SpotlessDaemonStartContext

    private companion object {
        val daemonStartupTimeout: Duration = 60.seconds
        val defaultDaemonCleanupTimeout: Duration = 5.seconds
        val defaultProviderRemovalCleanupTimeout: Duration = 2.seconds
    }
}
