/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.spotless

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.ExtensionPointListener
import com.intellij.openapi.extensions.PluginDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider
import dev.ghostflyby.spotless.api.SpotlessDaemonTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

internal class SpotlessProviderHandle(
    val provider: SpotlessDaemonProvider,
) {
    private val active = AtomicBoolean(true)

    val isAttached: Boolean
        get() = active.get()

    fun deactivate() {
        active.set(false)
    }
}

internal data class SpotlessProviderCatalogEntry(
    val handle: SpotlessProviderHandle,
    val presentableName: String,
    val externalProjects: List<Path>,
)

internal data class SpotlessProviderCatalogSnapshot(
    val providers: List<SpotlessProviderCatalogEntry> = emptyList(),
)

internal data class SpotlessProviderTarget(
    val handle: SpotlessProviderHandle,
    val target: SpotlessDaemonTarget,
)

internal class SpotlessProviderCatalog(
    private val project: Project,
    private val scope: CoroutineScope,
    private val onProviderStateChanged: suspend (SpotlessProviderHandle, List<Path>) -> Unit,
    private val onProviderRemoved: (SpotlessProviderHandle) -> Unit,
) {
    private data class ProviderRegistration(
        val handle: SpotlessProviderHandle,
        val job: Job,
        val commandScope: CoroutineScope,
        val state: StateFlow<SpotlessDaemonProvider.State>,
    )

    private val logger = logger<SpotlessProviderCatalog>()
    private val providerLock = Any()
    private val registrations = IdentityHashMap<SpotlessDaemonProvider, ProviderRegistration>()
    private var orderedRegistrations: List<ProviderRegistration> = emptyList()
    private val mutableSnapshot = MutableStateFlow(SpotlessProviderCatalogSnapshot())

    val snapshot: StateFlow<SpotlessProviderCatalogSnapshot> = mutableSnapshot.asStateFlow()

    internal var providersLookup: (Project) -> List<SpotlessDaemonProvider> =
        { SpotlessDaemonProvider.EP_NAME.extensionList }
        set(value) {
            field = value
            refresh()
        }

    init {
        SpotlessDaemonProvider.EP_NAME.point.addExtensionPointListener(
            scope,
            false,
            object : ExtensionPointListener<SpotlessDaemonProvider> {
                override fun extensionAdded(
                    extension: SpotlessDaemonProvider,
                    pluginDescriptor: PluginDescriptor,
                ) {
                    refresh()
                }

                override fun extensionRemoved(
                    extension: SpotlessDaemonProvider,
                    pluginDescriptor: PluginDescriptor,
                ) {
                    detachProvider(extension)
                    publishSnapshot()
                }
            },
        )
        refresh()
    }

    fun refresh() {
        val providers = currentProviders()
        val detached = synchronized(providerLock) {
            registrations.keys
                .filter { registered -> providers.none { it === registered } }
                .toList()
        }
        detached.forEach { provider ->
            detachProvider(provider)
        }
        providers.forEach(::attachProvider)
        synchronized(providerLock) {
            orderedRegistrations = providers.mapNotNull(registrations::get)
        }
        publishSnapshot()
    }

    fun resolveTarget(file: VirtualFile): SpotlessProviderTarget? {
        snapshot.value.providers.forEach { entry ->
            val provider = entry.handle.provider
            val target = runCatching {
                provider.resolveTarget(project, file)
            }.onFailure { error ->
                logger.debug("Failed to resolve Spotless daemon target", error)
            }.getOrNull() ?: return@forEach

            val externalProject = target.externalProject.toAbsolutePath().normalize()
            if (externalProject in entry.externalProjects) {
                return SpotlessProviderTarget(
                    handle = entry.handle,
                    target = target.copy(externalProject = externalProject),
                )
            }
            logger.warn(
                "Spotless provider returned a target outside its detected external projects: " +
                        "${provider.javaClass.name}: $externalProject",
            )
        }
        return null
    }

    fun findHandle(
        provider: SpotlessDaemonProvider,
        externalProject: Path? = null,
    ): SpotlessProviderHandle? {
        val normalizedExternalProject = externalProject?.toAbsolutePath()?.normalize()
        return snapshot.value.providers
            .firstOrNull { entry ->
                entry.handle.provider === provider &&
                        (normalizedExternalProject == null || normalizedExternalProject in entry.externalProjects)
            }
            ?.handle
    }

    fun launchProviderCommand(
        provider: SpotlessDaemonProvider,
        command: suspend (SpotlessProviderHandle) -> Unit,
    ): Job {
        val registration = synchronized(providerLock) {
            registrations[provider]
        } ?: return completedJob()
        return registration.commandScope.launch(Dispatchers.IO) {
            command(registration.handle)
        }
    }

    fun launchProviderCommand(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
        command: suspend (SpotlessProviderHandle, Path) -> Unit,
    ): Job {
        val normalizedExternalProject = externalProject.toAbsolutePath().normalize()
        val registration = synchronized(providerLock) {
            registrations[provider]
        } ?: return completedJob()
        val valid = normalizedExternalProject in normalizeExternalProjects(registration.state.value)
        if (!valid) {
            return completedJob()
        }
        return registration.commandScope.launch(Dispatchers.IO) {
            command(registration.handle, normalizedExternalProject)
        }
    }

    fun dispose() {
        val providerJobs = synchronized(providerLock) {
            registrations.values.onEach { registration ->
                registration.handle.deactivate()
            }.map(ProviderRegistration::job).also {
                registrations.clear()
                orderedRegistrations = emptyList()
            }
        }
        providerJobs.forEach(Job::cancel)
        mutableSnapshot.value = SpotlessProviderCatalogSnapshot()
    }

    private fun currentProviders(): List<SpotlessDaemonProvider> = buildList {
        providersLookup(project).forEach { provider ->
            if (none { it === provider }) {
                add(provider)
            }
        }
    }

    private fun attachProvider(provider: SpotlessDaemonProvider) {
        synchronized(providerLock) {
            if (registrations.containsKey(provider)) {
                return
            }
        }
        val state = runCatching {
            provider.state(project)
        }.onFailure { error ->
            logger.warn("Failed to obtain Spotless provider state", error)
        }.getOrNull() ?: return
        val providerJob = SupervisorJob(scope.coroutineContext[Job])
        val providerScope = CoroutineScope(scope.coroutineContext + providerJob)
        val registration = ProviderRegistration(
            handle = SpotlessProviderHandle(provider),
            job = providerJob,
            commandScope = providerScope,
            state = state,
        )
        val attached = synchronized(providerLock) {
            if (registrations.containsKey(provider)) {
                false
            } else {
                registrations[provider] = registration
                orderedRegistrations = orderedRegistrations + registration
                true
            }
        }
        if (!attached) {
            providerJob.cancel()
            return
        }
        providerScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            observeProviderState(registration)
        }
    }

    private fun detachProvider(
        provider: SpotlessDaemonProvider,
    ) {
        val registration = synchronized(providerLock) {
            registrations.remove(provider)?.also { removed ->
                orderedRegistrations = orderedRegistrations.filterNot { it === removed }
            }
        } ?: return
        registration.handle.deactivate()
        registration.job.cancel()
        runCatching {
            onProviderRemoved(registration.handle)
        }.onFailure { error ->
            logger.warn("Failed to release removed Spotless provider", error)
        }
    }

    private suspend fun observeProviderState(registration: ProviderRegistration) {
        registration.state.drop(1).collect { state ->
            publishSnapshot()
            try {
                onProviderStateChanged(
                    registration.handle,
                    normalizeExternalProjects(state),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("Failed to apply Spotless provider state", error)
            }
        }
    }

    private fun publishSnapshot() {
        val registrations = synchronized(providerLock) {
            orderedRegistrations
        }
        mutableSnapshot.value = SpotlessProviderCatalogSnapshot(
            registrations.map { registration ->
                SpotlessProviderCatalogEntry(
                    handle = registration.handle,
                    presentableName = providerPresentableName(registration.handle.provider),
                    externalProjects = normalizeExternalProjects(registration.state.value),
                )
            },
        )
    }

    private fun normalizeExternalProjects(state: SpotlessDaemonProvider.State): List<Path> = runCatching {
        state.externalProjects
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .sortedBy(Path::toString)
    }.onFailure { error ->
        logger.warn("Failed to inspect Spotless provider external projects", error)
    }.getOrDefault(emptyList())

    private fun providerPresentableName(provider: SpotlessDaemonProvider): String = runCatching {
        provider.presentableName.takeIf(String::isNotBlank)
    }.onFailure { error ->
        logger.warn("Failed to get Spotless provider name", error)
    }.getOrNull() ?: provider.javaClass.simpleName

    private fun completedJob(): Job = Job().apply { complete() }
}
