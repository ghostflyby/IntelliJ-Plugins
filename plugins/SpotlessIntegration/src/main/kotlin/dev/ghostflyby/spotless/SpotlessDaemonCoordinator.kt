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
import dev.ghostflyby.spotless.api.SpotlessDaemonProviderState
import dev.ghostflyby.spotless.api.SpotlessDaemonTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import java.util.*

internal class ProviderSession(
    val provider: SpotlessDaemonProvider,
    val scope: CoroutineScope,
    initialSnapshot: ProviderSnapshot,
) {
    @Volatile
    var snapshot: ProviderSnapshot = initialSnapshot

    val isAttached: Boolean
        get() = scope.coroutineContext.job.isActive
}

internal data class ProviderSnapshot(
    val generations: Map<Path, Long> = emptyMap(),
) {
    val externalProjects: List<Path>
        get() = generations.keys.toList()
}

internal data class ProviderDescriptor(
    val session: ProviderSession,
    val presentableName: String,
    val snapshot: ProviderSnapshot,
)

internal data class ProvidersSnapshot(
    val providers: List<ProviderDescriptor> = emptyList(),
)

internal data class ProviderTarget(
    val session: ProviderSession,
    val target: SpotlessDaemonTarget,
    val generation: Long,
)

internal data class SpotlessProviderStatus(
    val session: ProviderSession,
    val presentableName: String,
    val externalProjects: List<Path>,
    val runtimeStates: Map<Path, SpotlessDaemonRuntimeState>,
) {
    val provider: SpotlessDaemonProvider
        get() = session.provider
}

internal data class SpotlessDaemonStatusSnapshot(
    val providers: List<SpotlessProviderStatus> = emptyList(),
)

internal class SpotlessDaemonCoordinator(
    private val project: Project,
    private val scope: CoroutineScope,
    private val clientProvider: () -> SpotlessDaemonClient,
) {
    private val logger = logger<SpotlessDaemonCoordinator>()
    private val registry = SpotlessDaemonRegistry(project, scope, clientProvider)
    private val providerLock = Any()
    private val sessions = IdentityHashMap<SpotlessDaemonProvider, ProviderSession>()
    private var orderedSessions: List<ProviderSession> = emptyList()
    private val mutableProviders = MutableStateFlow(ProvidersSnapshot())

    val snapshot: StateFlow<SpotlessDaemonStatusSnapshot> = combine(
        mutableProviders,
        registry.runtimeState,
        ::createStatusSnapshot,
    ).stateIn(
        scope = scope,
        started = SharingStarted.Eagerly,
        initialValue = createStatusSnapshot(mutableProviders.value, registry.runtimeState.value),
    )

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
                }
            },
        )
        refresh()
    }

    fun refresh() {
        val providers = currentProviders()
        val detached = synchronized(providerLock) {
            sessions.keys
                .filter { registered -> providers.none { it === registered } }
                .toList()
        }
        detached.forEach(::detachProvider)
        providers.forEach(::attachProvider)
        synchronized(providerLock) {
            orderedSessions = providers.mapNotNull(sessions::get)
        }
        publishProviders()
    }

    fun resolveTarget(file: VirtualFile): ProviderTarget? {
        mutableProviders.value.providers.forEach { descriptor ->
            val provider = descriptor.session.provider
            val target = runCatching {
                provider.resolveTarget(project, file)
            }.onFailure { error ->
                logger.debug("Failed to resolve Spotless daemon target", error)
            }.getOrNull() ?: return@forEach

            val externalProject = target.externalProjectRoot.toAbsolutePath().normalize()
            val generation = descriptor.snapshot.generations[externalProject]
            if (generation != null) {
                return ProviderTarget(
                    session = descriptor.session,
                    target = target.copy(externalProjectRoot = externalProject),
                    generation = generation,
                )
            }
            logger.warn(
                "Spotless provider returned a target outside its detected external projects: " +
                        "${provider.javaClass.name}: $externalProject",
            )
        }
        return null
    }

    suspend fun <T> withDaemon(
        target: ProviderTarget,
        operation: suspend DaemonConnection.() -> T,
    ): T = registry.withDaemon(
        session = target.session,
        externalProject = target.target.externalProjectRoot,
        generation = target.generation,
    ) { endpoint ->
        DaemonConnection(endpoint).operation()
    }

    internal inner class DaemonConnection internal constructor(
        private val endpoint: SpotlessDaemonProvider.Endpoint,
    ) {
        suspend fun steps(path: Path): List<String>? =
            clientProvider().steps(endpoint, path)

        suspend fun format(
            path: Path,
            content: CharSequence,
            skipSteps: List<String>,
        ): SpotlessFormatResult = clientProvider().format(endpoint, path, content, skipSteps)
    }

    suspend fun releaseAllDaemons(): Int = registry.releaseAllDaemons()

    fun releaseAllDaemonsAsync(onReleased: (Int) -> Unit = {}): Job =
        scope.launch(Dispatchers.IO) {
            onReleased(releaseAllDaemons())
        }

    fun releaseDaemons(provider: SpotlessDaemonProvider): Job =
        launchProviderCommand(provider) { session ->
            registry.releaseDaemonsForSession(session)
        }

    fun releaseDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): Job = launchProviderCommand(provider, externalProject) { session, normalizedExternalProject, _ ->
        registry.releaseDaemon(session, normalizedExternalProject)
    }

    fun restartDaemon(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
    ): Job = launchProviderCommand(provider, externalProject) { session, normalizedExternalProject, generation ->
        registry.restartDaemon(session, normalizedExternalProject, generation)
    }

    fun hasRunningDaemons(): Boolean = registry.hasRunningDaemons()

    fun dispose() {
        val detachedSessions = synchronized(providerLock) {
            sessions.values.toList().also {
                sessions.clear()
                orderedSessions = emptyList()
            }
        }
        detachedSessions.forEach { it.scope.cancel() }
        mutableProviders.value = ProvidersSnapshot()
        registry.dispose()
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
            if (sessions.containsKey(provider)) {
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
        val session = ProviderSession(
            provider = provider,
            scope = providerScope,
            initialSnapshot = normalizeProviderState(state.value),
        )
        val attached = synchronized(providerLock) {
            if (sessions.containsKey(provider)) {
                false
            } else {
                sessions[provider] = session
                orderedSessions = orderedSessions + session
                true
            }
        }
        if (!attached) {
            providerJob.cancel()
            return
        }
        providerScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            observeProviderState(session, state)
        }
    }

    private fun detachProvider(provider: SpotlessDaemonProvider) {
        val session = synchronized(providerLock) {
            sessions.remove(provider)?.also { removed ->
                orderedSessions = orderedSessions.filterNot { it === removed }
            }
        } ?: return
        session.scope.cancel()
        runCatching {
            registry.releaseSessionSynchronously(session)
        }.onFailure { error ->
            logger.warn("Failed to release removed Spotless provider", error)
        }
        publishProviders()
    }

    private suspend fun observeProviderState(
        session: ProviderSession,
        state: StateFlow<SpotlessDaemonProviderState>,
    ) {
        state.collect { providerState ->
            val previous = session.snapshot
            val current = normalizeProviderState(providerState)
            if (current == previous) {
                return@collect
            }
            session.snapshot = current
            publishProviders()
            try {
                registry.reconcile(session, previous, current)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.warn("Failed to apply Spotless provider state", error)
            }
        }
    }

    private fun launchProviderCommand(
        provider: SpotlessDaemonProvider,
        command: suspend (ProviderSession) -> Unit,
    ): Job {
        val session = synchronized(providerLock) {
            sessions[provider]
        } ?: return completedJob()
        return session.scope.launch(Dispatchers.IO) {
            command(session)
        }
    }

    private fun launchProviderCommand(
        provider: SpotlessDaemonProvider,
        externalProject: Path,
        command: suspend (ProviderSession, Path, Long) -> Unit,
    ): Job {
        val normalizedExternalProject = externalProject.toAbsolutePath().normalize()
        val session = synchronized(providerLock) {
            sessions[provider]
        } ?: return completedJob()
        val generation = session.snapshot.generations[normalizedExternalProject] ?: return completedJob()
        return session.scope.launch(Dispatchers.IO) {
            command(session, normalizedExternalProject, generation)
        }
    }

    private fun publishProviders() {
        val sessions = synchronized(providerLock) {
            orderedSessions
        }
        mutableProviders.value = ProvidersSnapshot(
            sessions.map { session ->
                ProviderDescriptor(
                    session = session,
                    presentableName = providerPresentableName(session.provider),
                    snapshot = session.snapshot,
                )
            },
        )
    }

    private fun normalizeProviderState(state: SpotlessDaemonProviderState): ProviderSnapshot {
        val generations = linkedMapOf<Path, Long>()
        runCatching {
            state.projects.forEach { externalProject ->
                val root = externalProject.root.toAbsolutePath().normalize()
                val previous = generations.putIfAbsent(root, externalProject.generation)
                if (previous != null && previous != externalProject.generation) {
                    logger.warn("Spotless provider returned duplicate generations for external project: $root")
                }
            }
        }.onFailure { error ->
            logger.warn("Failed to inspect Spotless provider external projects", error)
            return ProviderSnapshot()
        }
        return ProviderSnapshot(
            generations.entries
                .sortedBy { (root, _) -> root.toString() }
                .associateTo(linkedMapOf()) { it.toPair() },
        )
    }

    private fun providerPresentableName(provider: SpotlessDaemonProvider): String = runCatching {
        provider.presentableName.takeIf(String::isNotBlank)
    }.onFailure { error ->
        logger.warn("Failed to get Spotless provider name", error)
    }.getOrNull() ?: provider.javaClass.simpleName

    private fun createStatusSnapshot(
        providerSnapshot: ProvidersSnapshot,
        runtimeSnapshot: SpotlessDaemonRuntimeSnapshot,
    ): SpotlessDaemonStatusSnapshot {
        val runtimeByProvider = runtimeSnapshot.entries.groupBy(SpotlessDaemonRuntimeEntry::session)
        return SpotlessDaemonStatusSnapshot(
            providerSnapshot.providers.mapNotNull { provider ->
                val externalProjects = provider.snapshot.externalProjects
                if (externalProjects.isEmpty()) {
                    return@mapNotNull null
                }
                val runtimeStates = runtimeByProvider[provider.session].orEmpty()
                    .associate { entry -> entry.externalProject to entry.state }
                    .filterKeys(externalProjects::contains)
                SpotlessProviderStatus(
                    session = provider.session,
                    presentableName = provider.presentableName,
                    externalProjects = externalProjects,
                    runtimeStates = runtimeStates,
                )
            },
        )
    }

    private fun completedJob(): Job = Job().apply { complete() }
}
