/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.toBuilder
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.projectModel.modifyGradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntityBuilder
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncContributor
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncPhase
import org.jetbrains.plugins.gradle.service.syncAction.virtualFileUrl
import org.jetbrains.plugins.gradle.settings.GradleSettingsListener
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong

internal class TypesafeConventionsProjectResolverExtension : AbstractProjectResolverExtension() {

    override fun getExtraProjectModelClasses(): Set<Class<out Any>> =
        setOf(TypesafeConventionsCatalogModel::class.java)

    override fun getToolingExtensionsClasses(): Set<Class<out Any>> =
        setOf(TypesafeConventionsCatalogModelBuilder::class.java)
}

// GradleSyncContributor and Gradle workspace entities are experimental IntelliJ APIs.
// This path keeps TOML support on Workspace Model data instead of private Gradle catalog extension points.
internal class TypesafeConventionsGradleSyncContributor :
    @Suppress("UnstableApiUsage")
    GradleSyncContributor {

    @Suppress("UnstableApiUsage")
    override val phase: GradleSyncPhase = GradleSyncPhase.ADDITIONAL_MODEL_PHASE

    @Suppress("UnstableApiUsage")
    override suspend fun createProjectModel(
        context: ProjectResolverContext,
        storage: ImmutableEntityStorage,
    ): ImmutableEntityStorage {
        val enabledBuildUrls = mutableSetOf<String>()
        val catalogUrls = mutableSetOf<String>()
        val workspaceBuilder = lazy(storage::toBuilder)
        val buildEntitiesByUrl = storage.entities<@Suppress("UnstableApiUsage") GradleBuildEntity>()
            .associateBy { it.url.url }

        for (buildModel in context.allBuilds) {
            val model = context.getBuildModel(buildModel, TypesafeConventionsCatalogModel::class.java)
            if (model == null) {
                LOG.warn("Typesafe conventions model is unavailable for ${buildModel.buildIdentifier.rootDir}")
                return rejectCandidate(context, storage)
            }
            when (model.status) {
                TypesafeConventionsCatalogModelStatus.DISABLED -> continue
                TypesafeConventionsCatalogModelStatus.INCOMPLETE -> {
                    model.diagnostics.forEach { diagnostic ->
                        LOG.warn(
                            "Typesafe conventions model is incomplete for " +
                                    "${buildModel.buildIdentifier.rootDir}: " +
                                    "${diagnostic.code}: ${diagnostic.message}",
                        )
                    }
                    return rejectCandidate(context, storage)
                }

                TypesafeConventionsCatalogModelStatus.COMPLETE -> Unit
            }
            if (model.catalogs.isEmpty()) {
                continue
            }

            val buildUrl = context.virtualFileUrl(buildModel.buildIdentifier.rootDir)
            val buildEntity = buildEntitiesByUrl[buildUrl.url]
            if (buildEntity == null) {
                LOG.warn(
                    "Typesafe conventions model for ${buildModel.buildIdentifier.rootDir} has no Gradle build entity",
                )
                return rejectCandidate(context, storage)
            }
            val newCatalogs = mutableListOf<GradleVersionCatalogEntityBuilder>()
            for ((catalogName, catalogPath) in model.catalogs) {
                val path = parseCatalogPath(catalogPath)
                if (path == null) {
                    LOG.warn(
                        "Typesafe conventions catalog $catalogName for ${buildModel.buildIdentifier.rootDir} " +
                                "has an invalid absolute path: $catalogPath",
                    )
                    return rejectCandidate(context, storage)
                }
                val catalogUrl = context.virtualFileUrl(path)
                catalogUrls.add(catalogUrl.url)
                val existingCatalog = buildEntity.versionCatalogs.firstOrNull { it.name == catalogName }
                if (existingCatalog != null) {
                    if (existingCatalog.url != catalogUrl) {
                        LOG.warn(
                            "Typesafe conventions catalog $catalogName for ${buildModel.buildIdentifier.rootDir} " +
                                    "does not match the Gradle catalog entity URL: " +
                                    "model=${catalogUrl.url}, workspace=${existingCatalog.url.url}",
                        )
                        return rejectCandidate(context, storage)
                    }
                    continue
                }
                newCatalogs.add(GradleVersionCatalogEntity(catalogName, catalogUrl, buildEntity.entitySource))
            }
            enabledBuildUrls.add(buildUrl.url)
            if (newCatalogs.isNotEmpty()) {
                workspaceBuilder.value.modifyGradleBuildEntity(buildEntity) {
                    versionCatalogs = versionCatalogs + newCatalogs
                }
            }
        }
        val state = context.project.service<TypesafeConventionsGradleBuildState>()
        state.stageCandidate(
            context.projectPath,
            TypesafeConventionsGradleBuildCandidate(
                buildUrls = enabledBuildUrls,
                catalogUrls = catalogUrls,
            ),
        )
        return if (workspaceBuilder.isInitialized()) workspaceBuilder.value.toSnapshot() else storage
    }

    private fun rejectCandidate(
        context: ProjectResolverContext,
        storage: ImmutableEntityStorage,
    ): ImmutableEntityStorage {
        context.project.service<TypesafeConventionsGradleBuildState>().discard(context.projectPath)
        return storage
    }

    private fun parseCatalogPath(catalogPath: String): Path? =
        try {
            Path.of(catalogPath).takeIf(Path::isAbsolute)
        } catch (_: InvalidPathException) {
            null
        }

    private companion object {
        private val LOG = logger<TypesafeConventionsGradleSyncContributor>()
    }
}

internal data class TypesafeConventionsGradleBuildCandidate(
    val buildUrls: Set<String>,
    val catalogUrls: Set<String>,
)

internal data class TypesafeConventionsGradleBuildPersistentState(
    val buildUrlsByProjectPath: Map<String, List<String>> = emptyMap(),
)

@State(
    name = "TypesafeConventionsGradleBuildState",
    storages = [Storage(StoragePathMacros.CACHE_FILE, roamingType = RoamingType.LOCAL)],
)
@Service(Service.Level.PROJECT)
internal class TypesafeConventionsGradleBuildState :
    SerializablePersistentStateComponent<TypesafeConventionsGradleBuildPersistentState>(
        TypesafeConventionsGradleBuildPersistentState(),
    ) {

    private val pendingCandidates = linkedMapOf<String, TypesafeConventionsGradleBuildCandidate>()

    fun stageCandidate(projectPath: String, candidate: TypesafeConventionsGradleBuildCandidate) {
        val normalizedProjectPath = projectPath.normalizedGradleProjectPath()
        synchronized(pendingCandidates) {
            pendingCandidates[normalizedProjectPath] = candidate.normalized()
        }
    }

    fun commit(projectPath: String?): Set<String>? = synchronized(pendingCandidates) {
        val projectPaths = if (projectPath == null) {
            pendingCandidates.keys.toSet()
        } else {
            setOf(projectPath.normalizedGradleProjectPath()).filterTo(mutableSetOf()) {
                it in pendingCandidates
            }
        }
        if (projectPaths.isEmpty()) {
            return@synchronized null
        }

        val candidates = projectPaths.associateWith { pendingCandidates.getValue(it) }
        val currentState = state
        val nextBuildUrls = currentState.buildUrlsByProjectPath.toMutableMap()
        for ((candidateProjectPath, candidate) in candidates) {
            if (candidate.buildUrls.isEmpty()) {
                nextBuildUrls.remove(candidateProjectPath)
            } else {
                nextBuildUrls[candidateProjectPath] = candidate.buildUrls.sorted()
            }
        }
        val nextState = currentState.copy(buildUrlsByProjectPath = nextBuildUrls.toSortedMap())
        if (nextState != currentState) {
            updateState { nextState }
        }

        buildSet {
            for ((candidateProjectPath, candidate) in candidates) {
                addAll(candidate.catalogUrls)
                pendingCandidates.remove(candidateProjectPath)
            }
        }
    }

    fun discard(projectPath: String?) {
        synchronized(pendingCandidates) {
            if (projectPath == null) {
                pendingCandidates.clear()
            } else {
                pendingCandidates.remove(projectPath.normalizedGradleProjectPath())
            }
        }
    }

    fun remove(projectPath: String): Boolean {
        val normalizedProjectPath = projectPath.normalizedGradleProjectPath()
        return synchronized(pendingCandidates) {
            pendingCandidates.remove(normalizedProjectPath)
            val currentState = state
            val nextState = currentState.copy(
                buildUrlsByProjectPath = currentState.buildUrlsByProjectPath - normalizedProjectPath,
            )
            val persistentChanged = nextState != currentState
            if (persistentChanged) {
                updateState { nextState }
            }
            persistentChanged
        }
    }

    fun committedBuildUrls(): Set<String> =
        state.buildUrlsByProjectPath.values.flatMapTo(mutableSetOf()) { it }

    private fun TypesafeConventionsGradleBuildCandidate.normalized(): TypesafeConventionsGradleBuildCandidate =
        copy(
            buildUrls = buildUrls.toSortedSet(),
            catalogUrls = catalogUrls.toSortedSet(),
        )
}

internal class TypesafeConventionsProjectDataImportListener(private val project: Project) :
    ProjectDataImportListener,
    GradleSettingsListener {
    override fun onImportFinished(projectPath: String?) {
        project.service<TypesafeConventionsCatalogRefreshService>().importFinished(projectPath)
    }

    override fun onImportFailed(projectPath: String?, t: Throwable) {
        project.service<TypesafeConventionsCatalogRefreshService>().importFailed(projectPath)
        LOG.warn(
            "Typesafe conventions Gradle import failed for ${projectPath ?: "all linked projects"}; " +
                    "retaining the last-known-good catalog index",
            t,
        )
    }

    override fun onProjectsUnlinked(linkedProjectPaths: Set<String>) {
        project.service<TypesafeConventionsCatalogRefreshService>().projectsUnlinked(linkedProjectPaths)
    }

    private companion object {
        private val LOG = logger<TypesafeConventionsProjectDataImportListener>()
    }
}

@Service(Service.Level.PROJECT)
internal class TypesafeConventionsCatalogRefreshService(
    private val project: Project,
    scope: CoroutineScope,
) {
    private val stateLock = Any()
    private val retryCatalogUrls = sortedSetOf<String>()
    private val coordinator: TypesafeConventionsCatalogRefreshCoordinator

    init {
        coordinator = TypesafeConventionsCatalogRefreshCoordinator(
            scope = scope,
            refreshCatalogs = ::refreshCatalogs,
            publishIfCurrent = ::publishIfCurrent,
            onFailure = ::refreshFailed,
        )
    }

    internal fun importFinished(projectPath: String?) {
        synchronized(stateLock) {
            val catalogUrls = project.service<TypesafeConventionsGradleBuildState>().commit(projectPath)
                ?: return
            scheduleLocked(catalogUrls)
        }
    }

    internal fun importFailed(projectPath: String?) {
        synchronized(stateLock) {
            project.service<TypesafeConventionsGradleBuildState>().discard(projectPath)
        }
    }

    internal fun projectsUnlinked(linkedProjectPaths: Set<String>) {
        synchronized(stateLock) {
            val state = project.service<TypesafeConventionsGradleBuildState>()
            var changed = false
            for (linkedProjectPath in linkedProjectPaths) {
                if (state.remove(linkedProjectPath)) {
                    changed = true
                }
            }
            if (changed) {
                scheduleLocked(emptySet())
            }
        }
    }

    @TestOnly
    internal suspend fun awaitIdle() {
        coordinator.awaitIdle()
    }

    private fun scheduleLocked(catalogUrls: Set<String>) {
        coordinator.schedule(catalogUrls + retryCatalogUrls)
    }

    private suspend fun refreshCatalogs(catalogUrls: Set<String>) {
        if (catalogUrls.isEmpty()) {
            return
        }
        val catalogFiles = withContext(Dispatchers.IO) {
            val virtualFileManager = VirtualFileManager.getInstance()
            catalogUrls.mapNotNull(virtualFileManager::refreshAndFindFileByUrl).also { files ->
                if (files.isNotEmpty()) {
                    VfsUtil.markDirtyAndRefresh(false, false, false, *files.toTypedArray())
                }
            }
        }
        withContext(Dispatchers.EDT) {
            val fileDocumentManager = FileDocumentManager.getInstance()
            val psiDocumentManager = PsiDocumentManager.getInstance(project)
            catalogFiles.asSequence()
                .filter { it.isValid }
                .mapNotNull(fileDocumentManager::getCachedDocument)
                .filter(psiDocumentManager::isUncommited)
                .forEach(psiDocumentManager::commitDocument)
        }
    }

    private fun publishIfCurrent(generation: Long, refreshedCatalogUrls: Set<String>): Boolean =
        synchronized(stateLock) {
            if (coordinator.currentGeneration != generation) {
                return@synchronized false
            }
            project.service<TypesafeConventionsCatalogIndexService>()
                .rebuildAndPublish(forceInvalidate = refreshedCatalogUrls.isNotEmpty())
            retryCatalogUrls.removeAll(refreshedCatalogUrls)
            true
        }

    private fun refreshFailed(catalogUrls: Set<String>, throwable: Throwable) {
        synchronized(stateLock) {
            retryCatalogUrls.addAll(catalogUrls)
        }
        LOG.warn(
            "Failed to refresh typesafe conventions catalogs; retaining the last-known-good catalog index",
            throwable,
        )
    }

    private companion object {
        private val LOG = logger<TypesafeConventionsCatalogRefreshService>()
    }
}

internal class TypesafeConventionsCatalogRefreshCoordinator(
    scope: CoroutineScope,
    private val refreshCatalogs: suspend (Set<String>) -> Unit,
    private val publishIfCurrent: (Long, Set<String>) -> Boolean,
    private val onFailure: (Set<String>, Throwable) -> Unit,
) {
    private data class RefreshRequest(
        val generation: Long,
        val catalogUrls: Set<String>,
    )

    private val requests = Channel<RefreshRequest>(Channel.UNLIMITED)
    private val requestedGeneration = AtomicLong()
    private val completedGeneration = MutableStateFlow(0L)

    internal val currentGeneration: Long
        get() = requestedGeneration.get()

    init {
        scope.launch {
            processRequests()
        }
    }

    internal fun schedule(catalogUrls: Set<String>) {
        val generation = requestedGeneration.incrementAndGet()
        if (requests.trySend(RefreshRequest(generation, catalogUrls.toSortedSet())).isFailure) {
            completedGeneration.value = generation
        }
    }

    @TestOnly
    internal suspend fun awaitIdle() {
        val targetGeneration = requestedGeneration.get()
        completedGeneration.first { generation -> generation >= targetGeneration }
    }

    private suspend fun processRequests() {
        for (firstRequest in requests) {
            var request = firstRequest
            var latestGeneration = firstRequest.generation
            val refreshedCatalogUrls = sortedSetOf<String>()
            while (true) {
                val catalogUrlsToRefresh = sortedSetOf<String>()
                fun collect(nextRequest: RefreshRequest) {
                    latestGeneration = maxOf(latestGeneration, nextRequest.generation)
                    catalogUrlsToRefresh.addAll(nextRequest.catalogUrls - refreshedCatalogUrls)
                }

                collect(request)
                while (true) {
                    val queuedRequest = requests.tryReceive().getOrNull() ?: break
                    collect(queuedRequest)
                }

                try {
                    refreshCatalogs(catalogUrlsToRefresh)
                    refreshedCatalogUrls.addAll(catalogUrlsToRefresh)
                    if (publishIfCurrent(latestGeneration, refreshedCatalogUrls)) {
                        completedGeneration.value = latestGeneration
                        break
                    }
                    request = requests.receive()
                } catch (exception: CancellationException) {
                    throw exception
                } catch (throwable: Throwable) {
                    onFailure(refreshedCatalogUrls + catalogUrlsToRefresh, throwable)
                    completedGeneration.value = latestGeneration
                    break
                }
            }
        }
    }
}

private fun String.normalizedGradleProjectPath(): String =
    try {
        Path.of(this).toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')
    } catch (_: InvalidPathException) {
        this
    }
