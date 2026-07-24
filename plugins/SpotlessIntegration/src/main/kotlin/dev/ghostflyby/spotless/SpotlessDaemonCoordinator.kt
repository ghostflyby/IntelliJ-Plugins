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
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.*
import dev.ghostflyby.spotless.api.SpotlessDaemonProvider.Target
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.file.Path
import java.util.*

internal class ProviderSession(
    val id: String,
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
    val providerId: String,
    val snapshot: ProviderSnapshot,
)

internal data class ProvidersSnapshot(
    val providers: List<ProviderDescriptor> = emptyList(),
)

internal data class ProviderTarget(
    val session: ProviderSession,
    val target: Target,
    val generation: Long,
)

internal data class SpotlessProviderStatus(
    val providerId: String,
    val externalProjects: List<Path>,
    val runtimeStates: Map<Path, SpotlessDaemonRuntimeState>,
)

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
    private val statusLock = Any()
    private val sessions = IdentityHashMap<SpotlessDaemonProvider, ProviderSession>()
    private val sessionsById = mutableMapOf<String, ProviderSession>()
    private var orderedSessions: List<ProviderSession> = emptyList()
    private val mutableProviders = MutableStateFlow(ProvidersSnapshot())
    private val mutableSnapshot = MutableStateFlow(
        createStatusSnapshot(mutableProviders.value, registry.runtimeState.value),
    )

    val snapshot: StateFlow<SpotlessDaemonStatusSnapshot> = mutableSnapshot.asStateFlow()

    internal var providersLookup: (Project) -> List<SpotlessDaemonProvider> =
        { SpotlessDaemonProvider.EP_NAME.extensionList }
        set(value) {
            field = value
            refresh()
        }

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            registry.runtimeState.collect {
                publishStatusSnapshot()
            }
        }
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
                    refresh()
                }
            },
        )
        refresh()
    }

    fun refresh() {
        val providers = currentProviders()
        val detached = synchronized(providerLock) {
            sessions.entries
                .filter { (registered, session) ->
                    providers.none { candidate ->
                        candidate.provider === registered && candidate.id == session.id
                    }
                }
                .map(Map.Entry<SpotlessDaemonProvider, ProviderSession>::key)
                .toList()
        }
        detached.forEach(::detachProvider)
        providers.forEach(::attachProvider)
        synchronized(providerLock) {
            orderedSessions = providers.mapNotNull { candidate -> sessions[candidate.provider] }
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
                        "${descriptor.providerId}: $externalProject",
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
        operation(DaemonConnection(endpoint))
    }

    internal inner class DaemonConnection internal constructor(
        private val endpoint: Endpoint,
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

    fun releaseDaemons(providerId: String): Job =
        launchProviderCommand(providerId) { session ->
            registry.releaseDaemonsForSession(session)
        }

    fun releaseDaemon(
        providerId: String,
        externalProject: Path,
    ): Job = launchProviderCommand(providerId, externalProject) { session, normalizedExternalProject, _ ->
        registry.releaseDaemon(session, normalizedExternalProject)
    }

    fun restartDaemon(
        providerId: String,
        externalProject: Path,
    ): Job = launchProviderCommand(providerId, externalProject) { session, normalizedExternalProject, generation ->
        registry.restartDaemon(session, normalizedExternalProject, generation)
    }

    fun hasRunningDaemons(): Boolean = registry.hasRunningDaemons()

    fun dispose() {
        val detachedSessions = synchronized(providerLock) {
            sessions.values.toList().also {
                sessions.clear()
                sessionsById.clear()
                orderedSessions = emptyList()
            }
        }
        detachedSessions.forEach { it.scope.cancel() }
        publishProviders(ProvidersSnapshot())
        registry.dispose()
    }

    private fun currentProviders(): List<ProviderCandidate> = buildList {
        val providers = Collections.newSetFromMap(IdentityHashMap<SpotlessDaemonProvider, Boolean>())
        val providersById = mutableMapOf<String, SpotlessDaemonProvider>()
        providersLookup(project).forEach { provider ->
            if (!providers.add(provider)) {
                return@forEach
            }
            val providerId = providerId(provider) ?: return@forEach
            val retainedProvider = providersById.putIfAbsent(providerId, provider)
            if (retainedProvider != null) {
                logger.warn(
                    "Ignoring Spotless daemon provider id '$providerId' from ${provider.javaClass.name}; " +
                            "the earlier extension ${retainedProvider.javaClass.name} has priority",
                )
                return@forEach
            }
            add(ProviderCandidate(providerId, provider))
        }
    }

    private fun attachProvider(candidate: ProviderCandidate) {
        val provider = candidate.provider
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
            id = candidate.id,
            provider = provider,
            scope = providerScope,
            initialSnapshot = normalizeProviderState(state.value),
        )
        val attached = synchronized(providerLock) {
            val existingSession = sessionsById[candidate.id]
            !sessions.containsKey(provider) && if (existingSession != null) {
                logger.warn(
                    "Skipping Spotless daemon provider attachment for id '${candidate.id}' " +
                            "from ${provider.javaClass.name}; the active session belongs to " +
                            existingSession.provider.javaClass.name,
                )
                false
            } else {
                sessions[provider] = session
                sessionsById[candidate.id] = session
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
                sessionsById.remove(removed.id, removed)
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
        state: StateFlow<State>,
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
        providerId: String,
        command: suspend (ProviderSession) -> Unit,
    ): Job {
        val session = synchronized(providerLock) {
            sessionsById[providerId]
        } ?: return completedJob()
        return session.scope.launch(Dispatchers.IO) {
            command(session)
        }
    }

    private fun launchProviderCommand(
        providerId: String,
        externalProject: Path,
        command: suspend (ProviderSession, Path, Long) -> Unit,
    ): Job {
        val normalizedExternalProject = externalProject.toAbsolutePath().normalize()
        val session = synchronized(providerLock) {
            sessionsById[providerId]
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
        publishProviders(
            ProvidersSnapshot(
                sessions.map { session ->
                    ProviderDescriptor(
                        session = session,
                        providerId = session.id,
                        snapshot = session.snapshot,
                    )
                },
            ),
        )
    }

    private fun publishProviders(providers: ProvidersSnapshot) {
        synchronized(statusLock) {
            mutableProviders.value = providers
            mutableSnapshot.value = createStatusSnapshot(providers, registry.runtimeState.value)
        }
    }

    private fun publishStatusSnapshot() {
        synchronized(statusLock) {
            mutableSnapshot.value = createStatusSnapshot(mutableProviders.value, registry.runtimeState.value)
        }
    }

    private fun normalizeProviderState(state: State): ProviderSnapshot {
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

    private fun providerId(provider: SpotlessDaemonProvider): String? = runCatching {
        provider.id.takeIf(providerIdPattern::matches)
    }.onFailure { error ->
        logger.warn("Failed to get Spotless daemon provider id", error)
    }.getOrNull().also { providerId ->
        if (providerId == null) {
            logger.warn("Ignoring Spotless daemon provider with invalid id: ${provider.javaClass.name}")
        }
    }

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
                    providerId = provider.providerId,
                    externalProjects = externalProjects,
                    runtimeStates = runtimeStates,
                )
            },
        )
    }

    private fun completedJob(): Job = Job().apply { complete() }

    private data class ProviderCandidate(
        val id: String,
        val provider: SpotlessDaemonProvider,
    )

    private companion object {
        val providerIdPattern: Regex = Regex("[a-z][a-z0-9_-]*(?:\\.[a-z][a-z0-9_-]*)+")
    }
}
