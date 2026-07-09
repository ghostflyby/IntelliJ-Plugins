/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.platform.externalSystem.impl.workspaceModel.ExternalProjectEntityId
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.toBuilder
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntityId
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
        var changed = false

        for (buildModel in context.allBuilds) {
            val model = context.getBuildModel(buildModel, TypesafeConventionsCatalogModel::class.java)
                ?: continue
            if (!model.enabled || model.catalogs.isEmpty()) {
                continue
            }

            val buildUrl = context.virtualFileUrl(buildModel.buildIdentifier.rootDir)
            val buildId = GradleBuildEntityId(ExternalProjectEntityId(context.externalProjectPath), buildUrl)
            val buildEntity = builder.resolve(buildId) ?: continue
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
