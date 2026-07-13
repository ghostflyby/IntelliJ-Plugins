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
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString

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

    private data class DaemonEntry(
        val provider: SpotlessDaemonProvider,
        val handle: SpotlessDaemonHandle,
    )

    private val entries = ConcurrentHashMap<RegistryKey, DaemonEntry>()

    suspend fun getDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): SpotlessDaemonHost {
        val key = key(externalProject)
        val current = entries[key]
        if (current != null) {
            if (current.provider === provider) {
                return current.handle.host
            }
            if (entries.remove(key, current)) {
                capabilityCache.invalidateExternalProject(key.externalProjectPath)
                scheduleStop(current, "provider switched")
            }
        }

        val handle = provider.startDaemon(project, externalProject)
        val newEntry = DaemonEntry(provider, handle)
        val raceEntry = entries.putIfAbsent(key, newEntry)
        if (raceEntry != null) {
            scheduleStop(newEntry, "daemon race lost")
            return raceEntry.handle.host
        }
        return handle.host
    }

    fun releaseDaemon(host: SpotlessDaemonHost) {
        val removed = removeEntries { it.handle.host == host }
        removed.forEach { (key, entry) ->
            capabilityCache.invalidateExternalProject(key.externalProjectPath)
            scheduleStop(entry, "external release")
        }
    }

    fun releaseAllDaemons(): Int {
        val removed = removeEntries { true }
        removed.forEach { (key, entry) ->
            capabilityCache.invalidateExternalProject(key.externalProjectPath)
            scheduleStop(entry, "project release")
        }
        return removed.size
    }

    fun hasRunningDaemons(): Boolean = entries.isNotEmpty()

    fun dispose() {
        val removed = removeEntries { true }
        capabilityCache.clear()
        runBlocking(Dispatchers.IO) {
            withContext(NonCancellable) {
                removed.values.map { entry ->
                    async {
                        stopDaemonEntry(entry, "service disposed")
                    }
                }.awaitAll()
            }
        }
    }

    private fun removeEntries(predicate: (DaemonEntry) -> Boolean): Map<RegistryKey, DaemonEntry> {
        val matches = entries.entries
            .filter { predicate(it.value) }
            .map { it.key to it.value }
        val removed = LinkedHashMap<RegistryKey, DaemonEntry>()
        matches.forEach { (key, entry) ->
            if (entries.remove(key, entry)) {
                removed[key] = entry
            }
        }
        return removed
    }

    private fun scheduleStop(entry: DaemonEntry, reason: String) {
        scope.launch(Dispatchers.IO) {
            stopDaemonEntry(entry, reason)
        }
    }

    private suspend fun stopDaemonEntry(entry: DaemonEntry, reason: String) {
        val host = entry.handle.host
        val stopFailure = runCatching {
            clientProvider().stop(host)
        }.exceptionOrNull()
        runCatching {
            entry.handle.cleanup(reason)
        }.onFailure { error ->
            logger.warn("Provider daemon cleanup failed ($reason): $host", error)
        }
        if (stopFailure != null) {
            logger.warn("Failed to stop daemon ($reason): $host", stopFailure)
        }
    }

    private fun key(externalProject: Path): RegistryKey =
        RegistryKey(
            externalProjectPath = externalProject.normalize().absolutePathString(),
        )
}
