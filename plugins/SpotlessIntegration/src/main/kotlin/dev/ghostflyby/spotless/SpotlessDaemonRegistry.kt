/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import dev.ghostflyby.spotless.api.SpotlessDaemonEndpoint
import dev.ghostflyby.spotless.api.SpotlessDaemonHandle
import dev.ghostflyby.spotless.api.SpotlessDaemonStartContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
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

    private data class DaemonKey(
        val session: ProviderSession,
        val root: Path,
    )

    private class DaemonExecution(
        val key: DaemonKey,
        val generation: Long,
        parentContext: CoroutineContext,
        parentJob: Job,
    ) {
        val job: CompletableJob = SupervisorJob(parentJob)
        val scope = CoroutineScope(parentContext + job)
        var ownership: HandleOwnership = HandleOwnership.None
        lateinit var startupTask: Deferred<SpotlessDaemonHandle>
    }

    private sealed interface HandleOwnership {
        data object None : HandleOwnership

        data class Active(
            val handle: SpotlessDaemonHandle,
            val completion: Deferred<Unit>,
        ) : HandleOwnership
    }

    private sealed interface DaemonEntry {
        val generation: Long
        val execution: DaemonExecution
    }

    private data class Starting(
        override val generation: Long,
        override val execution: DaemonExecution,
    ) : DaemonEntry

    private data class Ready(
        override val generation: Long,
        override val execution: DaemonExecution,
        val handle: SpotlessDaemonHandle,
    ) : DaemonEntry

    private data class ProviderTermination(
        val failure: Throwable?,
    )

    private enum class ReadyPublication {
        Published,
        ProviderEnded,
        Detached,
    }

    private data class ReadyLease(
        val key: DaemonKey,
        val entry: Ready,
    )

    private data class DetachedDaemon(
        val key: DaemonKey,
        val entry: DaemonEntry,
        val reason: String,
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
        operation: suspend (SpotlessDaemonEndpoint) -> T,
    ): T {
        val ready = getDaemon(session, externalProject, generation)
        try {
            return operation(ready.entry.handle.endpoint)
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
                    staleDeadline = cleanupDeadline(daemonCleanupTimeout)
                    stale = detachLocked(
                        key,
                        current,
                        "provider generation changed",
                        stopHttp = true,
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
                    current.execution.startupTask.start()
                    current.execution.startupTask.await()
                }
            }
        }
    }

    private fun createStartingLocked(
        key: DaemonKey,
        generation: Long,
    ): Starting {
        val execution = DaemonExecution(
            key = key,
            generation = generation,
            parentContext = registryScope.coroutineContext,
            parentJob = registryJob,
        )
        execution.startupTask = execution.scope.async(Dispatchers.IO, start = CoroutineStart.LAZY) {
            startExecution(execution)
        }
        execution.startupTask.invokeOnCompletion {
            execution.job.complete()
        }
        return Starting(generation, execution).also { entry ->
            entries[key] = entry
            publishRuntimeStateLocked()
        }
    }

    private suspend fun providerHandleEnded(
        execution: DaemonExecution,
        handle: SpotlessDaemonHandle,
        completion: Deferred<Unit>,
        failure: Throwable?,
    ): Boolean = entriesMutex.withLock {
        val entry = entries[execution.key]
        val ownership = execution.ownership
        if (
            entry?.execution !== execution ||
            ownership !is HandleOwnership.Active ||
            ownership.handle !== handle ||
            ownership.completion !== completion
        ) {
            return@withLock false
        }
        detachLocked(
            execution.key,
            entry,
            reason = if (failure == null) "provider handle ended" else "provider handle failed",
            stopHttp = false,
        )
        publishRuntimeStateLocked()
        true
    }

    private suspend fun startExecution(execution: DaemonExecution): SpotlessDaemonHandle {
        var providerEnded = false
        try {
            val returned = execution.key.session.provider.startDaemon(DaemonStartContext(execution))
            val active = entriesMutex.withLock {
                val ownership = execution.ownership
                ownership as? HandleOwnership.Active
            }
            check(
                active != null &&
                        active.handle === returned &&
                        active.handle.lifetime === active.completion,
            ) {
                "Spotless daemon provider returned a handle not created by the current start context"
            }
            val completion = active.completion

            val termination = awaitReadyWhileRunning(returned, completion)
            if (termination != null) {
                providerEnded = true
                throw providerEndedFailure(termination.failure)
            }

            val publication = withContext(NonCancellable) {
                entriesMutex.withLock {
                    val current = entries[execution.key]
                    when {
                        current !is Starting ||
                                current.execution !== execution ||
                                current.generation != execution.generation ||
                                !execution.key.session.isAttached ||
                                execution.key.session.snapshot.generations[execution.key.root] != execution.generation ->
                            ReadyPublication.Detached

                        completion.isCompleted -> ReadyPublication.ProviderEnded

                        else -> {
                            entries[execution.key] = Ready(execution.generation, execution, returned)
                            publishRuntimeStateLocked()
                            ReadyPublication.Published
                        }
                    }
                }
            }
            when (publication) {
                ReadyPublication.Published -> return returned
                ReadyPublication.Detached ->
                    throw CancellationException("Spotless daemon was detached while starting")

                ReadyPublication.ProviderEnded -> {
                    providerEnded = true
                    throw providerEndedFailure(completionFailure(completion))
                }
            }
        } catch (error: Throwable) {
            val detached = withContext(NonCancellable) {
                entriesMutex.withLock {
                    val current = entries[execution.key]
                    if (current is Starting && current.execution === execution) {
                        detachLocked(
                            execution.key,
                            current,
                            if (providerEnded) "provider handle ended during startup" else "daemon start failed",
                            stopHttp = !providerEnded,
                        ).also { publishRuntimeStateLocked() }
                    } else {
                        null
                    }
                }
            }
            if (!providerEnded && detached != null) {
                closeFailedStartup(
                    detached = detached,
                    deadline = cleanupDeadline(daemonCleanupTimeout),
                )
            }
            throw error
        }
    }

    private suspend fun awaitReadyWhileRunning(
        handle: SpotlessDaemonHandle,
        completion: Deferred<Unit>,
    ): ProviderTermination? = coroutineScope {
        val readiness = async(Dispatchers.IO) {
            clientProvider().awaitReady(handle.endpoint, daemonStartupTimeout)
        }
        try {
            select {
                completion.onJoin {
                    ProviderTermination(completionFailure(completion))
                }
                readiness.onAwait { null }
            }
        } finally {
            readiness.cancel()
        }
    }

    private suspend fun completionFailure(completion: Deferred<Unit>): Throwable? =
        runCatching { completion.await() }.exceptionOrNull()

    private fun providerEndedFailure(failure: Throwable?): Throwable =
        failure ?: IllegalStateException("Spotless daemon provider ended before its endpoint became ready")

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
                ).also { publishRuntimeStateLocked() }
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
        val detached = detachEntries(predicate, reason)
        closeAll(detached, deadline)
        return detached.size
    }

    private suspend fun detachEntries(
        predicate: (Map.Entry<DaemonKey, DaemonEntry>) -> Boolean,
        reason: String,
    ): List<DetachedDaemon> = entriesMutex.withLock {
        entries.entries
            .filter(predicate)
            .map { (key, entry) ->
                detachLocked(key, entry, reason, stopHttp = true)
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
    ): DetachedDaemon {
        check(entries.remove(key, entry))
        capabilityCache.invalidateExternalProject(key.root.absolutePathString())
        return DetachedDaemon(key, entry, reason, stopHttp)
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
                async { closeDetached(daemon, deadline) }
            }.awaitAll()
        }
    }

    private suspend fun closeDetached(
        detached: DetachedDaemon,
        deadline: CleanupDeadline,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            val execution = detached.entry.execution
            val endpoint = execution.endpointOrNull()
            if (detached.stopHttp && endpoint != null) {
                stopEndpoint(endpoint, detached.reason, deadline.remaining())
            }

            val cancellation = CancellationException("Spotless daemon detached: ${detached.reason}")
            execution.job.cancel(cancellation)
            joinJob(execution.job, detached.key, deadline.remaining())
            if (deadline.remaining() <= Duration.ZERO) {
                logger.warn("Timed out cleaning up Spotless daemon: ${detached.key}")
            }
        }
    }

    private suspend fun closeFailedStartup(
        detached: DetachedDaemon,
        deadline: CleanupDeadline,
    ) {
        withContext(NonCancellable + Dispatchers.IO) {
            val execution = detached.entry.execution
            val endpoint = execution.endpointOrNull()
            if (detached.stopHttp && endpoint != null) {
                stopEndpoint(endpoint, detached.reason, deadline.remaining())
            }

            val cancellation = CancellationException("Spotless daemon startup failed: ${detached.reason}")
            execution.activeCompletionOrNull()?.let { completion ->
                completion.cancel(cancellation)
                joinJob(completion, detached.key, deadline.remaining())
            }
            if (deadline.remaining() <= Duration.ZERO) {
                logger.warn("Timed out cleaning up failed Spotless daemon startup: ${detached.key}")
            }
        }
    }

    private suspend fun joinJob(
        job: Job,
        key: DaemonKey,
        timeout: Duration,
    ) {
        if (timeout <= Duration.ZERO) {
            logger.warn("Timed out waiting for Spotless daemon provider cleanup: $key")
            return
        }
        val completed = withTimeoutOrNull(timeout) {
            job.join()
            true
        } ?: false
        if (!completed) {
            logger.warn("Timed out waiting for Spotless daemon provider cleanup: $key")
        }
    }

    private suspend fun stopEndpoint(
        endpoint: SpotlessDaemonEndpoint,
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

    private fun DaemonExecution.endpointOrNull(): SpotlessDaemonEndpoint? =
        when (val current = ownership) {
            HandleOwnership.None -> null
            is HandleOwnership.Active -> current.handle.endpoint
        }

    private fun DaemonExecution.activeCompletionOrNull(): Deferred<Unit>? =
        (ownership as? HandleOwnership.Active)?.completion

    private fun observeHandleCompletion(
        execution: DaemonExecution,
        handle: SpotlessDaemonHandle,
        completion: Deferred<Unit>,
    ) {
        completion.invokeOnCompletion { failure ->
            registryScope.launch(Dispatchers.IO) {
                val wasCurrent = withContext(NonCancellable) {
                    providerHandleEnded(execution, handle, completion, failure)
                }
                if (wasCurrent && failure != null && failure !is CancellationException) {
                    logger.warn("Spotless daemon provider failed: ${execution.key}", failure)
                }
            }
        }
    }

    private inner class DaemonStartContext(
        private val execution: DaemonExecution,
    ) : SpotlessDaemonStartContext {
        override val project: Project
            get() = this@SpotlessDaemonRegistry.project

        override val externalProjectRoot: Path
            get() = execution.key.root

        @Suppress("OPT_IN_USAGE")
        override suspend fun launchHandle(
            endpoint: SpotlessDaemonEndpoint,
            lifetime: suspend () -> Unit,
        ): SpotlessDaemonHandle {
            currentCoroutineContext().ensureActive()
            entriesMutex.lock()
            return try {
                withContext(NonCancellable) {
                    val entry = entries[execution.key]
                    if (entry?.execution !== execution) {
                        throw CancellationException("Spotless daemon was detached before launching its handle")
                    }
                    ensureCurrentProviderState(execution.key.session, execution.key.root, execution.generation)
                    check(execution.ownership === HandleOwnership.None) {
                        "Spotless daemon provider launched more than one handle"
                    }

                    // ATOMIC guarantees that a cancellation racing with ownership transfer still enters
                    // the provider lifetime block, so its finally clause can release acquired resources.
                    val lifetimeTask = execution.scope.async(
                        context = Dispatchers.IO,
                        start = CoroutineStart.ATOMIC,
                    ) {
                        lifetime()
                    }
                    val handle = SpotlessDaemonHandle(endpoint, lifetimeTask)
                    execution.ownership = HandleOwnership.Active(handle, lifetimeTask)
                    observeHandleCompletion(execution, handle, lifetimeTask)
                    handle
                }
            } finally {
                entriesMutex.unlock()
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
                    .thenBy { it.session.id },
            ),
        )
    }

    private companion object {
        val daemonStartupTimeout: Duration = 60.seconds
        val defaultDaemonCleanupTimeout: Duration = 5.seconds
        val defaultProviderRemovalCleanupTimeout: Duration = 2.seconds
    }
}
