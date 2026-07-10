/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.externalSystem.service.project.manage.ProjectDataImportListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.externalSystem.impl.workspaceModel.ExternalProjectEntityId
import com.intellij.platform.workspace.jps.entities.ModuleEntity
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
// This path keeps TOML support on Workspace Model data instead of private Gradle catalog extension points.
internal class TypesafeConventionsGradleSyncContributor : GradleSyncContributor {

    override val phase: GradleSyncPhase = GradleSyncPhase.ADDITIONAL_MODEL_PHASE

    override suspend fun createProjectModel(
        context: ProjectResolverContext,
        storage: ImmutableEntityStorage,
    ): ImmutableEntityStorage {
        val builder = storage.toBuilder()
        val entitySource = TypesafeConventionsEntitySource(
            projectPath = context.projectPath,
            phase = phase,
            kind = TypesafeConventionsEntityKind.CATALOG,
        )
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
        repairTypesafeConventionsModuleLinks(project)
    }

    override fun onFinalTasksFinished(projectPath: String?) {
        repairTypesafeConventionsModuleLinks(project)
    }
}

private fun repairTypesafeConventionsModuleLinks(project: Project) {
    val enabledBuilds = project.service<TypesafeConventionsGradleBuildState>().enabledBuilds
    if (enabledBuilds.isEmpty()) {
        return
    }
    refreshTypesafeConventionsCatalogFiles(project, enabledBuilds.keys)
    runWriteAction {
        project.workspaceModel.updateProjectModel("Link Gradle source-set modules for typesafe conventions catalogs") { builder ->
            linkSourceSetModulesToGradleProject(
                builder = builder,
                affectedProjects = collectTypesafeConventionsGradleProjects(builder, enabledBuilds),
            )
        }
    }
}

@Suppress("UnstableApiUsage")
private fun refreshTypesafeConventionsCatalogFiles(
    project: Project,
    enabledBuildUrls: Set<String>,
) {
    val virtualFileManager = VirtualFileManager.getInstance()
    project.workspaceModel.currentSnapshot.entities<GradleBuildEntity>()
        .filter { it.url.url in enabledBuildUrls }
        .flatMap { it.versionCatalogs }
        .forEach { catalog ->
            virtualFileManager.refreshAndFindFileByUrl(catalog.url.url)
        }
}

@Suppress("UnstableApiUsage")
private fun collectTypesafeConventionsGradleProjects(
    builder: MutableEntityStorage,
    enabledBuilds: Map<String, String>,
): List<TypesafeConventionsGradleProjectLink> =
    builder.entities<GradleBuildEntity>()
        .flatMap { build ->
            val projectPath = enabledBuilds[build.url.url]
                ?: return@flatMap emptyList()
            val linkSource = TypesafeConventionsEntitySource(
                projectPath = projectPath,
                phase = GradleSyncPhase.ADDITIONAL_MODEL_PHASE,
                kind = TypesafeConventionsEntityKind.MODULE_LINK,
            )
            build.projects.map { TypesafeConventionsGradleProjectLink(it, linkSource) }
        }
        .toList()

private fun linkSourceSetModulesToGradleProject(
    builder: MutableEntityStorage,
    affectedProjects: Collection<TypesafeConventionsGradleProjectLink>,
): Boolean {
    @Suppress("UnstableApiUsage") val projects = affectedProjects
        .sortedByDescending { it.project.url.url.length }
        .toList()
    if (projects.isEmpty()) {
        return false
    }

    var changed = false
    for (module in builder.entities<ModuleEntity>().toList()) {
        if (module.gradleModuleEntity != null) {
            continue
        }
        @Suppress("UnstableApiUsage") val projectLink = projects.firstOrNull { projectLink ->
            module.contentRoots.any { contentRoot ->
                contentRoot.url.url.isUnderOrEqual(projectLink.project.url.url)
            }
        }
            ?: continue

        builder.modifyModuleEntity(module) {
            @Suppress("UnstableApiUsage")
            gradleModuleEntity = GradleModuleEntity(projectLink.project.symbolicId, projectLink.entitySource)
        }
        changed = true
    }
    return changed
}

private fun String.isUnderOrEqual(rootUrl: String): Boolean =
    this == rootUrl || startsWith(rootUrl.trimEnd('/') + "/")

private data class TypesafeConventionsGradleProjectLink(
    @Suppress("UnstableApiUsage") val project: GradleProjectEntity,
    val entitySource: EntitySource,
)

@Suppress("UnstableApiUsage")
private data class TypesafeConventionsEntitySource(
    override val projectPath: String,
    @Suppress("UnstableApiUsage") override val phase: GradleSyncPhase,
    val kind: TypesafeConventionsEntityKind,
) : GradleEntitySource

private enum class TypesafeConventionsEntityKind {
    CATALOG,
    MODULE_LINK,
}
