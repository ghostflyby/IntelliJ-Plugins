/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.platform.workspace.storage.toBuilder
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

    override suspend fun createProjectModel(
        context: ProjectResolverContext,
        storage: ImmutableEntityStorage,
    ): ImmutableEntityStorage {
        val builder = storage.toBuilder()
        val enabledBuilds = mutableMapOf<String, String>()
        var changed = false

        for (buildModel in context.allBuilds) {
            val model = context.getBuildModel(buildModel, TypesafeConventionsCatalogModel::class.java)
                ?: continue
            if (!model.enabled || model.catalogs.isEmpty()) {
                continue
            }

            val buildUrl = context.virtualFileUrl(buildModel.buildIdentifier.rootDir)
            enabledBuilds[buildUrl.url] = context.projectPath
            val buildEntity = builder.entities<@Suppress("UnstableApiUsage") GradleBuildEntity>().firstOrNull {
                @Suppress("UnstableApiUsage")
                it.url == buildUrl
            } ?: continue
            val existingNames = buildEntity.versionCatalogs.mapTo(mutableSetOf()) { it.name }
            val newCatalogs = model.catalogs.mapNotNull { (catalogName, catalogPath) ->
                if (catalogName in existingNames) {
                    return@mapNotNull null
                }
                createCatalogEntity(context, catalogName, catalogPath, buildEntity.entitySource)
            }

            if (newCatalogs.isNotEmpty()) {
                builder.modifyGradleBuildEntity(buildEntity) {
                    versionCatalogs = versionCatalogs + newCatalogs
                }
                changed = true
            }
        }
        context.project.service<TypesafeConventionsGradleBuildState>().enabledBuilds = enabledBuilds

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

@Service(Service.Level.PROJECT)
internal class TypesafeConventionsGradleBuildState {
    @Volatile
    var enabledBuilds: Map<String, String> = emptyMap()
}

internal class TypesafeConventionsProjectDataImportListener(private val project: Project) : ProjectDataImportListener {
    override fun onImportFinished(projectPath: String?) {
        refreshTypesafeConventionsCatalogFiles(project)
    }

    override fun onFinalTasksFinished(projectPath: String?) {
        refreshTypesafeConventionsCatalogFiles(project)
    }
}

private fun refreshTypesafeConventionsCatalogFiles(project: Project) {
    val enabledBuildUrls = project.service<TypesafeConventionsGradleBuildState>().enabledBuilds.keys
    if (enabledBuildUrls.isEmpty()) {
        return
    }
    val virtualFileManager = VirtualFileManager.getInstance()
    @Suppress("UnstableApiUsage")
    project.workspaceModel.currentSnapshot.entities<GradleBuildEntity>()
        .filter {
            @Suppress("UnstableApiUsage")
            it.url.url in enabledBuildUrls
        }
        .flatMap { it.versionCatalogs }
        .forEach { catalog ->
            virtualFileManager.refreshAndFindFileByUrl(catalog.url.url)
        }
}

