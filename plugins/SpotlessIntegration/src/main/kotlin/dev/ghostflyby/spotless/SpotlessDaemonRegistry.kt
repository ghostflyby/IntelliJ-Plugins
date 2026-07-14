/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.file.Path
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
internal class SpotlessDaemonRegistry(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    internal var clientProvider: () -> SpotlessDaemonClient = { SpotlessDaemonClient() }

    private val capabilityCache = project.service<SpotlessCapabilityCache>()
    private val logger = logger<SpotlessDaemonRegistry>()

    private data class RegistryKey(
        val externalProjectPath: String,
    )

    private sealed interface DaemonEntry {
        val provider: SpotlessDaemonProvider
        val attachment: SpotlessDaemonAttachment
    }

    private data class StartingDaemonEntry(
        override val provider: SpotlessDaemonProvider,
        override val attachment: SpotlessDaemonAttachment,
        val result: CompletableDeferred<SpotlessDaemonHost>,
    ) : DaemonEntry

    private data class RunningDaemonEntry(
        override val provider: SpotlessDaemonProvider,
        override val attachment: SpotlessDaemonAttachment,
        val host: SpotlessDaemonHost,
    ) : DaemonEntry

    private data class DetachedDaemonEntry(
        val entry: DaemonEntry,
        val cleanups: List<() -> Unit>,
        val stopHttp: Boolean,
    )

    private sealed interface DaemonResolution {
        data class Start(val entry: StartingDaemonEntry) : DaemonResolution
        data class Await(val result: Deferred<SpotlessDaemonHost>) : DaemonResolution
        data class Return(val host: SpotlessDaemonHost) : DaemonResolution
        data class Replace(val detached: DetachedDaemonEntry) : DaemonResolution
    }

    /* The concurrent map makes cheap visibility checks safe; all mutations use entriesMutex. */
    private val entries = ConcurrentHashMap<RegistryKey, DaemonEntry>()
    private val entriesMutex = Mutex()

    suspend fun getDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): SpotlessDaemonHost {
        val key = key(externalProject)
        while (true) {
            when (val resolution = entriesMutex.withLock {
                when (val current = entries[key]) {
                    null -> {
                        val attachment = SpotlessDaemonAttachment(key)
                        val entry = StartingDaemonEntry(provider, attachment, CompletableDeferred())
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
                            DaemonResolution.Return(current.host)
                        } else {
                            DaemonResolution.Replace(detachLocked(key, current, "provider switched", stopHttp = true))
                        }
                    }
                }
            }) {
                is DaemonResolution.Await -> return resolution.result.await()
                is DaemonResolution.Return -> return resolution.host
                is DaemonResolution.Start -> return startDaemon(key, resolution.entry, externalProject)
                is DaemonResolution.Replace -> closeDetached(resolution.detached)
            }
        }
    }

    suspend fun releaseAllDaemons(): Int {
        val detached = detachEntries({ true }, "project release")
        detached.forEach(::scheduleClose)
        return detached.size
    }

    fun releaseDaemonsForProviderSynchronously(provider: SpotlessDaemonProvider): Int =
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                val detached = detachEntries(
                    predicate = { it.provider === provider },
                    reason = "provider extension removed",
                )
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
    ): SpotlessDaemonHost {
        val host = try {
            entry.provider.startDaemon(project, externalProject, entry.attachment)
        } catch (error: Throwable) {
            entriesMutex.withLock {
                if (entries[key] === entry) {
                    detachLocked(key, entry, "daemon start failed", false)
                } else {
                    null
                }
            }?.let { detached ->
                closeDetached(detached)
            }
            entry.result.completeExceptionally(error)
            throw error
        }

        val published = entriesMutex.withLock {
            if (entries[key] === entry && entry.attachment.isOpen()) {
                entries[key] = RunningDaemonEntry(entry.provider, entry.attachment, host)
                entry.result.complete(host)
                true
            } else {
                entry.result.completeExceptionally(
                    CancellationException("Spotless daemon was detached while starting"),
                )
                false
            }
        }
        if (published) {
            return host
        }

        if (entry.attachment.shouldStopReturnedHost()) {
            stopHost(host, "daemon detached while starting", null)
        }
        throw CancellationException("Spotless daemon was detached while starting")
    }

    private suspend fun detachEntries(
        predicate: (DaemonEntry) -> Boolean,
        reason: String,
    ): List<DetachedDaemonEntry> = entriesMutex.withLock {
        entries.entries
            .filter { predicate(it.value) }
            .mapNotNull { (key, entry) ->
                if (entries[key] === entry) detachLocked(key, entry, reason, true) else null
            }
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
            entry.result.completeExceptionally(CancellationException("Spotless daemon detached: $reason"))
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
        withContext(NonCancellable) {
            val host = (detached.entry as? RunningDaemonEntry)?.host
            if (detached.stopHttp && host != null) {
                stopHost(host, detached.entry.attachment.closeReason(), stopTimeout)
            }
            detached.entry.attachment.runCleanups(detached.cleanups)
        }
    }

    private suspend fun stopHost(
        host: SpotlessDaemonHost,
        reason: String,
        timeout: Duration?,
    ) {
        val stopped = runCatching {
            if (timeout == null) {
                clientProvider().stop(host)
                true
            } else {
                withTimeoutOrNull(timeout) {
                    clientProvider().stop(host)
                    true
                } ?: false
            }
        }
        stopped.exceptionOrNull()?.let { error ->
            logger.warn("Failed to stop daemon ($reason): $host", error)
        }
        if (stopped.getOrNull() == false) {
            logger.warn("Timed out stopping daemon ($reason): $host")
        }
    }

    private fun key(externalProject: Path): RegistryKey =
        RegistryKey(externalProject.normalize().absolutePathString())

    private inner class SpotlessDaemonAttachment(
        private val key: RegistryKey,
    ) : SpotlessDaemonLifecycle {
        private val cleanupLock = Any()
        private val cleanups = ArrayDeque<() -> Unit>()
        private var open = true
        private var closeReason = "daemon detached"
        private var stopReturnedHost = false

        override fun requestClose(reason: String) {
            val detached = runBlocking {
                entriesMutex.withLock {
                    val entry = entries[key]
                    if (entry?.attachment === this@SpotlessDaemonAttachment) {
                        detachLocked(key, entry, reason, stopHttp = false)
                    } else {
                        null
                    }
                }
            } ?: return
            runBlocking(Dispatchers.IO) {
                closeDetached(detached)
            }
        }

        override fun onClose(cleanup: () -> Unit) {
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
                stopReturnedHost = stopHttp
                val snapshot = cleanups.toList()
                cleanups.clear()
                snapshot
            }
        }

        fun isOpen(): Boolean = synchronized(cleanupLock) { open }

        fun shouldStopReturnedHost(): Boolean = synchronized(cleanupLock) { stopReturnedHost }

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
}
