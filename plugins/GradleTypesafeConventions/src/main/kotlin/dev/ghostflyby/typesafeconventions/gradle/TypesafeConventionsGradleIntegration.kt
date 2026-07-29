/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.toBuilder
import com.intellij.platform.workspace.storage.url.VirtualFileUrl
import com.intellij.psi.PsiDocumentManager
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.projectModel.modifyGradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntityBuilder
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncContributor
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncPhase
import org.jetbrains.plugins.gradle.service.syncAction.virtualFileUrl
import org.jetbrains.plugins.gradle.settings.GradleSettingsListener
import java.io.File
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class TypesafeConventionsProjectResolverExtension : AbstractProjectResolverExtension(),
    GradleProjectResolverExtension {

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
        val preparedBuilds = mutableListOf<TypesafeConventionsPreparedBuild>()
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
                newCatalogs.add(createCatalogEntity(catalogName, catalogUrl, buildEntity.entitySource))
            }
            enabledBuildUrls.add(buildUrl.url)
            preparedBuilds.add(TypesafeConventionsPreparedBuild(buildEntity, newCatalogs))
        }

        val result = if (preparedBuilds.any { it.newCatalogs.isNotEmpty() }) {
            val builder = storage.toBuilder()
            for ((buildEntity, newCatalogs) in preparedBuilds) {
                if (newCatalogs.isEmpty()) {
                    continue
                }
                builder.modifyGradleBuildEntity(buildEntity) {
                    versionCatalogs = versionCatalogs + newCatalogs
                }
            }
            builder.toSnapshot()
        } else {
            storage
        }
        val state = context.project.service<TypesafeConventionsGradleBuildState>()
        state.stageCandidate(
            context.projectPath,
            TypesafeConventionsGradleBuildCandidate(
                buildUrls = enabledBuildUrls,
                catalogUrls = catalogUrls,
            ),
        )
        return result
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

    private fun createCatalogEntity(
        catalogName: String,
        catalogUrl: VirtualFileUrl,
        entitySource: EntitySource,
    ): GradleVersionCatalogEntityBuilder =
        GradleVersionCatalogEntity(catalogName, catalogUrl, entitySource)

    private companion object {
        private val LOG = logger<TypesafeConventionsGradleSyncContributor>()
    }
}

@Suppress("UnstableApiUsage")
private data class TypesafeConventionsPreparedBuild(
    val buildEntity: GradleBuildEntity,
    val newCatalogs: List<GradleVersionCatalogEntityBuilder>,
)

internal data class TypesafeConventionsGradleBuildCandidate(
    val buildUrls: Set<String>,
    val catalogUrls: Set<String>,
)

internal data class TypesafeConventionsGradleBuildCommit(
    val projectPaths: Set<String>,
    val catalogUrlsToRefresh: Set<String>,
)

internal data class TypesafeConventionsGradleBuildPersistentState(
    val buildUrlsByProjectPath: Map<String, List<String>> = emptyMap(),
)

internal enum class TypesafeConventionsGradleProjectPathHealth {
    UNKNOWN,
    CURRENT,
    STALE,
}

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
    private val committedCatalogUrls = linkedMapOf<String, Set<String>>()
    private val projectPathHealth = linkedMapOf<String, TypesafeConventionsGradleProjectPathHealth>()

    fun stageCandidate(projectPath: String, candidate: TypesafeConventionsGradleBuildCandidate) {
        val normalizedProjectPath = projectPath.normalizedGradleProjectPath()
        synchronized(pendingCandidates) {
            pendingCandidates[normalizedProjectPath] = candidate.normalized()
        }
    }

    fun commit(projectPath: String?): TypesafeConventionsGradleBuildCommit = synchronized(pendingCandidates) {
        val projectPaths = if (projectPath == null) {
            pendingCandidates.keys.toSet()
        } else {
            setOf(projectPath.normalizedGradleProjectPath()).filterTo(mutableSetOf()) {
                it in pendingCandidates
            }
        }
        if (projectPaths.isEmpty()) {
            return@synchronized TypesafeConventionsGradleBuildCommit(emptySet(), emptySet())
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

        val catalogUrlsToRefresh = buildSet {
            for ((candidateProjectPath, candidate) in candidates) {
                addAll(candidate.catalogUrls)
                if (candidate.catalogUrls.isEmpty()) {
                    committedCatalogUrls.remove(candidateProjectPath)
                } else {
                    committedCatalogUrls[candidateProjectPath] = candidate.catalogUrls
                }
                projectPathHealth[candidateProjectPath] = TypesafeConventionsGradleProjectPathHealth.CURRENT
                pendingCandidates.remove(candidateProjectPath)
            }
        }
        TypesafeConventionsGradleBuildCommit(projectPaths, catalogUrlsToRefresh)
    }

    fun discard(projectPath: String?) {
        synchronized(pendingCandidates) {
            val discardedProjectPaths = if (projectPath == null) {
                (pendingCandidates.keys + state.buildUrlsByProjectPath.keys).toSet()
            } else {
                setOf(projectPath.normalizedGradleProjectPath())
            }
            discardedProjectPaths.forEach { discardedProjectPath ->
                projectPathHealth[discardedProjectPath] = TypesafeConventionsGradleProjectPathHealth.STALE
                pendingCandidates.remove(discardedProjectPath)
            }
        }
    }

    fun remove(projectPath: String): Boolean {
        val normalizedProjectPath = projectPath.normalizedGradleProjectPath()
        return synchronized(pendingCandidates) {
            val pendingChanged = pendingCandidates.remove(normalizedProjectPath) != null
            val catalogChanged = committedCatalogUrls.remove(normalizedProjectPath) != null
            val healthChanged = projectPathHealth.remove(normalizedProjectPath) != null
            val currentState = state
            val nextState = currentState.copy(
                buildUrlsByProjectPath = currentState.buildUrlsByProjectPath - normalizedProjectPath,
            )
            val persistentChanged = nextState != currentState
            if (persistentChanged) {
                updateState { nextState }
            }
            pendingChanged || catalogChanged || healthChanged || persistentChanged
        }
    }

    fun projectPathHealth(projectPath: String): TypesafeConventionsGradleProjectPathHealth =
        synchronized(pendingCandidates) {
            projectPathHealth[projectPath.normalizedGradleProjectPath()]
                ?: TypesafeConventionsGradleProjectPathHealth.UNKNOWN
        }

    fun committedBuildUrls(): Set<String> =
        state.buildUrlsByProjectPath.values.flatMapTo(mutableSetOf()) { it }

    fun pendingProjectPaths(): Set<String> = synchronized(pendingCandidates) {
        pendingCandidates.keys.toSet()
    }

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
        val commit = project.service<TypesafeConventionsGradleBuildState>().commit(projectPath)
        if (commit.projectPaths.isEmpty()) {
            return
        }
        refreshTypesafeConventionsCatalogFiles(project, commit.catalogUrlsToRefresh)
        project.service<TypesafeConventionsCatalogIndexService>().apply {
            catalogsRefreshed()
            rebuildAndPublish()
        }
    }

    override fun onImportFailed(projectPath: String?, t: Throwable) {
        project.service<TypesafeConventionsGradleBuildState>().discard(projectPath)
        LOG.warn(
            "Typesafe conventions Gradle import failed for ${projectPath ?: "all linked projects"}; " +
                    "retaining the last-known-good catalog index",
            t,
        )
    }

    override fun onProjectsUnlinked(linkedProjectPaths: Set<String>) {
        val state = project.service<TypesafeConventionsGradleBuildState>()
        val changed = linkedProjectPaths.any(state::remove)
        if (changed) {
            project.service<TypesafeConventionsCatalogIndexService>().rebuildAndPublish()
        }
    }

    private companion object {
        private val LOG = logger<TypesafeConventionsProjectDataImportListener>()
    }
}

private fun refreshTypesafeConventionsCatalogFiles(project: Project, catalogUrls: Set<String>) {
    val virtualFileManager = VirtualFileManager.getInstance()
    val catalogFiles = catalogUrls.mapNotNull(virtualFileManager::refreshAndFindFileByUrl)
    VfsUtil.markDirtyAndRefresh(false, false, false, *catalogFiles.toTypedArray())

    val fileDocumentManager = FileDocumentManager.getInstance()
    val documents = catalogFiles.mapNotNull(fileDocumentManager::getCachedDocument)
    if (documents.isEmpty()) {
        return
    }
    val commitDocuments = Runnable {
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        documents.forEach { document ->
            if (psiDocumentManager.isUncommited(document)) {
                psiDocumentManager.commitDocument(document)
            }
        }
    }
    val application = ApplicationManager.getApplication()
    if (application.isDispatchThread) {
        commitDocuments.run()
    } else {
        application.invokeAndWait(commitDocuments)
    }
}

private fun String.normalizedGradleProjectPath(): String =
    try {
        Path.of(this).toAbsolutePath().normalize().toString().replace(File.separatorChar, '/')
    } catch (_: InvalidPathException) {
        this
    }
