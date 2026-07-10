/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.Key
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ProjectData
import com.intellij.openapi.externalSystem.service.project.manage.WorkspaceDataService
import com.intellij.openapi.project.Project
import com.intellij.platform.externalSystem.impl.workspaceModel.ExternalProjectEntityId
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.exModuleOptions
import com.intellij.platform.workspace.jps.entities.modifyModuleEntity
import com.intellij.platform.workspace.storage.*
import org.jetbrains.plugins.gradle.model.projectModel.*
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.GradleVersionCatalogEntityBuilder
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.GradleProjectResolverExtension
import org.jetbrains.plugins.gradle.service.project.ProjectResolverContext
import org.jetbrains.plugins.gradle.service.syncAction.GradleEntitySource
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncContributor
import org.jetbrains.plugins.gradle.service.syncAction.GradleSyncPhase
import org.jetbrains.plugins.gradle.service.syncAction.virtualFileUrl
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class TypesafeConventionsProjectResolverExtension : AbstractProjectResolverExtension(),
    GradleProjectResolverExtension {

    override fun getExtraProjectModelClasses(): Set<Class<out Any>> =
        setOf(TypesafeConventionsCatalogModel::class.java)

    override fun getToolingExtensionsClasses(): Set<Class<out Any>> =
        setOf(TypesafeConventionsCatalogModelBuilder::class.java)
}

@Suppress("UnstableApiUsage")
// GradleSyncContributor and Gradle workspace entities are experimental IntelliJ APIs.
// This path is intentionally used instead of the internal GradleVersionCatalogHandler extension point.
internal class TypesafeConventionsGradleSyncContributor : GradleSyncContributor {

    override val phase: GradleSyncPhase = GradleSyncPhase.ADDITIONAL_MODEL_PHASE

    override suspend fun createProjectModel(
        context: ProjectResolverContext,
        storage: ImmutableEntityStorage,
    ): ImmutableEntityStorage {
        val builder = storage.toBuilder()
        val entitySource = TypesafeConventionsEntitySource(context.projectPath, phase)
        val enabledBuildUrls = mutableSetOf<String>()
        var changed = false

        for (buildModel in context.allBuilds) {
            val model = context.getBuildModel(buildModel, TypesafeConventionsCatalogModel::class.java)
                ?: continue
            if (!model.enabled || model.catalogs.isEmpty()) {
                continue
            }

            val buildUrl = context.virtualFileUrl(buildModel.buildIdentifier.rootDir)
            enabledBuildUrls += buildUrl.url
            val buildId = GradleBuildEntityId(ExternalProjectEntityId(context.externalProjectPath), buildUrl)
            val buildEntity = builder.resolve(buildId) ?: continue
            val existingNames = buildEntity.versionCatalogs.mapTo(mutableSetOf()) { it.name }
            val newCatalogs = model.catalogs.mapNotNull { (catalogName, catalogPath) ->
                if (catalogName in existingNames) {
                    return@mapNotNull null
                }
                createCatalogEntity(context, catalogName, catalogPath, entitySource)
            }

            if (newCatalogs.isNotEmpty()) {
                builder.modifyGradleBuildEntity(buildEntity) {
                    versionCatalogs = versionCatalogs + newCatalogs
                }
                changed = true
            }
        }
        context.project.service<TypesafeConventionsGradleBuildState>().enabledBuildUrls = enabledBuildUrls

        return if (changed) builder.toSnapshot() else storage
    }

    private fun createCatalogEntity(
        context: ProjectResolverContext,
        catalogName: String,
        catalogPath: String,
        entitySource: EntitySource,
    ): GradleVersionCatalogEntityBuilder? {
        val path = try {
            Path.of(catalogPath)
        } catch (_: InvalidPathException) {
            return null
        }
        return GradleVersionCatalogEntity(catalogName, context.virtualFileUrl(path), entitySource)
    }
}

@Suppress("UnstableApiUsage")
// WorkspaceDataService runs after legacy module import has created source-set modules.
// It complements the sync contributor without using the internal GradleVersionCatalogHandler API.
internal class TypesafeConventionsGradleModuleDataService :
    WorkspaceDataService<TypesafeConventionsGradleModuleLinkData> {

    override fun getTargetDataKey(): Key<TypesafeConventionsGradleModuleLinkData> =
        TYPESAFE_CONVENTIONS_GRADLE_MODULE_LINK_KEY

    override fun importData(
        toImport: Collection<DataNode<TypesafeConventionsGradleModuleLinkData>>,
        projectData: ProjectData?,
        project: Project,
        mutableStorage: MutableEntityStorage,
    ) {
        if (projectData?.owner != GradleConstants.SYSTEM_ID) {
            return
        }

        val affectedProjectIds = mutableStorage.entities<GradleBuildEntity>()
            .filter { build -> build.url.url in project.service<TypesafeConventionsGradleBuildState>().enabledBuildUrls }
            .flatMapTo(mutableSetOf()) { build ->
                build.projects.map { it.symbolicId }
            }
        if (affectedProjectIds.isEmpty()) {
            return
        }

        linkSourceSetModulesToGradleProject(
            builder = mutableStorage,
            affectedProjectIds = affectedProjectIds,
            entitySource = TypesafeConventionsEntitySource(
                projectPath = projectData.linkedExternalProjectPath,
                phase = GradleSyncPhase.ADDITIONAL_MODEL_PHASE,
            ),
        )
    }
}

@Service(Service.Level.PROJECT)
internal class TypesafeConventionsGradleBuildState {
    @Volatile
    var enabledBuildUrls: Set<String> = emptySet()
}

internal class TypesafeConventionsGradleModuleLinkData

private val TYPESAFE_CONVENTIONS_GRADLE_MODULE_LINK_KEY: Key<TypesafeConventionsGradleModuleLinkData> =
    Key.create(
        TypesafeConventionsGradleModuleLinkData::class.java,
        ProjectKeys.MODULE.processingWeight + 2,
    )

private fun linkSourceSetModulesToGradleProject(
    builder: MutableEntityStorage,
    affectedProjectIds: Set<GradleProjectEntityId>,
    entitySource: EntitySource,
): Boolean {
    val gradleProjectIdsByModule = builder.entities<ModuleEntity>()
        .mapNotNull { module ->
            val options = module.exModuleOptions ?: return@mapNotNull null
            val gradleModuleEntity = module.gradleModuleEntity ?: return@mapNotNull null
            if (gradleModuleEntity.gradleProjectId !in affectedProjectIds) {
                return@mapNotNull null
            }
            createHolderModuleKey(
                rootProjectPath = options.rootProjectPath,
                linkedProjectPath = options.linkedProjectPath,
                linkedProjectId = options.linkedProjectId,
            )?.let { it to gradleModuleEntity.gradleProjectId }
        }
        .toMap()

    var changed = false
    for (module in builder.entities<ModuleEntity>().toList()) {
        if (module.gradleModuleEntity != null) {
            continue
        }
        val options = module.exModuleOptions ?: continue
        if (options.externalSystemModuleType != GradleConstants.GRADLE_SOURCE_SET_MODULE_TYPE_KEY) {
            continue
        }
        val linkedProjectId = options.linkedProjectId ?: continue
        val ownerLinkedProjectId = linkedProjectId.substringBeforeLast(':', missingDelimiterValue = "")
        if (ownerLinkedProjectId.isEmpty()) {
            continue
        }
        val holderModuleKey = createHolderModuleKey(
            rootProjectPath = options.rootProjectPath,
            linkedProjectPath = options.linkedProjectPath,
            linkedProjectId = ownerLinkedProjectId,
        ) ?: continue
        val gradleProjectId = gradleProjectIdsByModule[holderModuleKey] ?: continue

        builder.modifyModuleEntity(module) {
            gradleModuleEntity = GradleModuleEntity(gradleProjectId, entitySource)
        }
        changed = true
    }
    return changed
}

private fun createHolderModuleKey(
    rootProjectPath: String?,
    linkedProjectPath: String?,
    linkedProjectId: String?,
): HolderModuleKey? {
    if (rootProjectPath == null || linkedProjectPath == null || linkedProjectId == null) {
        return null
    }
    return HolderModuleKey(
        rootProjectPath = rootProjectPath,
        linkedProjectPath = linkedProjectPath,
        linkedProjectId = linkedProjectId,
    )
}

private data class HolderModuleKey(
    val rootProjectPath: String,
    val linkedProjectPath: String,
    val linkedProjectId: String,
)

private data class TypesafeConventionsEntitySource(
    override val projectPath: String,
    override val phase: GradleSyncPhase,
) : GradleEntitySource
