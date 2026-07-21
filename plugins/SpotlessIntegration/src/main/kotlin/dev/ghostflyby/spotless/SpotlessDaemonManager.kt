/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.nio.file.Path

internal data class SpotlessProviderStatus(
    val handle: SpotlessProviderHandle,
    val presentableName: String,
    val externalProjects: List<Path>,
    val runtimeStates: Map<Path, SpotlessDaemonRuntimeState>,
) {
    val provider: SpotlessDaemonProvider
        get() = handle.provider
}

internal data class SpotlessDaemonStatusSnapshot(
    val providers: List<SpotlessProviderStatus> = emptyList(),
)

internal class SpotlessDaemonManager(
    project: Project,
    private val scope: CoroutineScope,
    clientProvider: () -> SpotlessDaemonClient,
) {
    private val registry = SpotlessDaemonRegistry(
        project = project,
        scope = scope,
        clientProvider = clientProvider,
    )
    private val catalog = SpotlessProviderCatalog(
        project = project,
        scope = scope,
        onProviderStateChanged = registry::restartDaemonsForProvider,
        onProviderRemoved = registry::releaseDaemonsForProviderSynchronously,
    )

    val snapshot: StateFlow<SpotlessDaemonStatusSnapshot> = combine(
        catalog.snapshot,
        registry.runtimeState,
        ::createStatusSnapshot,
    ).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = createStatusSnapshot(catalog.snapshot.value, registry.runtimeState.value),
    )

    internal var providersLookup: (Project) -> List<SpotlessDaemonProvider>
        get() = catalog.providersLookup
        set(value) {
            catalog.providersLookup = value
        }

    fun refresh() {
        catalog.refresh()
    }

    fun resolveTarget(file: VirtualFile): SpotlessProviderTarget? = catalog.resolveTarget(file)

    suspend fun getDaemon(target: SpotlessProviderTarget): SpotlessDaemonProvider.Endpoint =
        registry.getDaemon(target.handle, target.target.externalProject)

    suspend fun getDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): SpotlessDaemonProvider.Endpoint {
        val handle = requireNotNull(catalog.findHandle(provider, externalProject)) {
            "Spotless provider is not registered for external project: $externalProject"
        }
        return registry.getDaemon(handle, externalProject)
    }

    suspend fun releaseAllDaemons(): Int = registry.releaseAllDaemons()

    fun releaseAllDaemonsAsync(onReleased: (Int) -> Unit = {}): Job =
        scope.launch(Dispatchers.IO) {
            onReleased(releaseAllDaemons())
        }

    fun releaseDaemons(provider: SpotlessDaemonProvider): Job =
        catalog.launchProviderCommand(provider) { handle ->
            registry.releaseDaemonsForProvider(handle)
        }

    fun releaseDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): Job = catalog.launchProviderCommand(provider, externalProject) { handle, normalizedExternalProject ->
        registry.releaseDaemon(handle, normalizedExternalProject)
    }

    fun restartDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): Job = catalog.launchProviderCommand(provider, externalProject) { handle, normalizedExternalProject ->
        registry.releaseDaemon(handle, normalizedExternalProject)
        registry.getDaemon(handle, normalizedExternalProject)
    }

    fun hasRunningDaemons(): Boolean = registry.hasRunningDaemons()

    fun dispose() {
        catalog.dispose()
        registry.dispose()
    }

    private fun createStatusSnapshot(
        catalogSnapshot: SpotlessProviderCatalogSnapshot,
        runtimeSnapshot: SpotlessDaemonRuntimeSnapshot,
    ): SpotlessDaemonStatusSnapshot {
        val runtimeByProvider = runtimeSnapshot.entries.groupBy(SpotlessDaemonRuntimeEntry::provider)
        return SpotlessDaemonStatusSnapshot(
            catalogSnapshot.providers.mapNotNull { provider ->
                if (provider.externalProjects.isEmpty()) {
                    return@mapNotNull null
                }
                val runtimeStates = runtimeByProvider[provider.handle].orEmpty()
                    .associate { entry -> entry.externalProject.normalize() to entry.state }
                    .filterKeys(provider.externalProjects::contains)
                SpotlessProviderStatus(
                    handle = provider.handle,
                    presentableName = provider.presentableName,
                    externalProjects = provider.externalProjects,
                    runtimeStates = runtimeStates,
                )
            },
        )
    }
}
