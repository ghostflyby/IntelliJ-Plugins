/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.toml.lang.psi.TomlFile

internal data class TypesafeConventionsCatalogIndexEntry(
    val catalogName: String,
    val catalogUrl: String,
    val buildUrl: String,
    val contextRootUrls: Set<String>,
) {
    fun matchLength(contextUrl: String): Int? = contextRootUrls
        .filter { rootUrl -> contextUrl == rootUrl || contextUrl.startsWith(rootUrl.trimEnd('/') + "/") }
        .maxOfOrNull(String::length)
}

internal class TypesafeConventionsCatalogIndex private constructor(
    private val catalogsByName: Map<String, List<TypesafeConventionsCatalogIndexEntry>>,
    private val buildRootUrlsByCatalogUrl: Map<String, Set<String>>,
) {
    fun findCatalog(contextUrl: String, catalogName: String): TypesafeConventionsCatalogIndexEntry? =
        catalogsByName[catalogName]
            .orEmpty()
            .mapNotNull { catalog -> catalog.matchLength(contextUrl)?.let { matchLength -> matchLength to catalog } }
            .maxWithOrNull(
                compareBy<Pair<Int, TypesafeConventionsCatalogIndexEntry>> { it.first }
                    .thenBy { it.second.buildUrl },
            )
            ?.second

    fun buildRootUrls(catalogUrl: String): Set<String> =
        buildRootUrlsByCatalogUrl[catalogUrl].orEmpty()

    internal companion object {
        fun create(entries: Collection<TypesafeConventionsCatalogIndexEntry>): TypesafeConventionsCatalogIndex =
            TypesafeConventionsCatalogIndex(
                catalogsByName = entries
                    .groupBy(TypesafeConventionsCatalogIndexEntry::catalogName)
                    .mapValues { (_, catalogs) -> catalogs.sortedBy { it.buildUrl } },
                buildRootUrlsByCatalogUrl = entries
                    .groupBy(TypesafeConventionsCatalogIndexEntry::catalogUrl)
                    .mapValues { (_, catalogs) -> catalogs.mapTo(sortedSetOf()) { it.buildUrl } },
            )
    }
}

internal class TypesafeConventionsCatalogIndexCache {
    @Volatile
    private var cached: CachedIndex? = null

    fun getOrBuild(
        snapshotIdentity: Any,
        stateModificationCount: Long,
        builder: () -> TypesafeConventionsCatalogIndex,
    ): TypesafeConventionsCatalogIndex {
        cached?.takeIf { cache ->
            cache.snapshotIdentity === snapshotIdentity &&
                    cache.stateModificationCount == stateModificationCount
        }?.let(CachedIndex::index)?.let { return it }

        return synchronized(this) {
            cached?.takeIf { cache ->
                cache.snapshotIdentity === snapshotIdentity &&
                        cache.stateModificationCount == stateModificationCount
            }?.index ?: builder().also { index ->
                cached = CachedIndex(snapshotIdentity, stateModificationCount, index)
            }
        }
    }

    private data class CachedIndex(
        val snapshotIdentity: Any,
        val stateModificationCount: Long,
        val index: TypesafeConventionsCatalogIndex,
    )
}

@Service(Service.Level.PROJECT)
internal class TypesafeConventionsCatalogIndexService(private val project: Project) {
    private val cache = TypesafeConventionsCatalogIndexCache()

    @RequiresReadLock
    fun currentIndex(): TypesafeConventionsCatalogIndex {
        val snapshot = project.workspaceModel.currentSnapshot
        val buildState = project.service<TypesafeConventionsGradleBuildState>()
        return cache.getOrBuild(snapshot, buildState.stateModificationCount) {
            buildIndex(snapshot, buildState.committedBuildUrls())
        }
    }

    @Suppress("UnstableApiUsage")
    private fun buildIndex(
        snapshot: ImmutableEntityStorage,
        enabledBuildUrls: Set<String>,
    ): TypesafeConventionsCatalogIndex {
        if (enabledBuildUrls.isEmpty()) {
            return TypesafeConventionsCatalogIndex.create(emptyList())
        }
        val entries = snapshot.entities<GradleBuildEntity>()
            .filter { build -> build.url.url in enabledBuildUrls }
            .flatMap { build ->
                val contextRootUrls = buildSet {
                    add(build.url.url)
                    build.projects.mapTo(this) { project -> project.url.url }
                }
                build.versionCatalogs.asSequence().map { catalog ->
                    TypesafeConventionsCatalogIndexEntry(
                        catalogName = catalog.name,
                        catalogUrl = catalog.url.url,
                        buildUrl = build.url.url,
                        contextRootUrls = contextRootUrls,
                    )
                }
            }
            .toList()
        return TypesafeConventionsCatalogIndex.create(entries)
    }
}

@RequiresReadLock
@RequiresBackgroundThread
internal fun findTypesafeConventionsCatalogTomlFile(context: PsiElement, catalogName: String): TomlFile? {
    val contextUrl = context.containingFile?.originalFile?.virtualFile?.url
        ?: context.containingFile?.virtualFile?.url
        ?: return null

    val catalogUrl = context.project.service<TypesafeConventionsCatalogIndexService>()
        .currentIndex()
        .findCatalog(contextUrl, catalogName)
        ?.catalogUrl
        ?: return null
    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(catalogUrl)
        ?: return null
    return context.manager.findFile(virtualFile) as? TomlFile
}

@RequiresReadLock
internal fun findTypesafeConventionsCatalogBuildRoots(catalogFile: TomlFile): List<VirtualFile> {
    val catalogUrl = catalogFile.originalFile.virtualFile?.url
        ?: catalogFile.virtualFile?.url
        ?: return emptyList()
    val virtualFileManager = VirtualFileManager.getInstance()
    return catalogFile.project.service<TypesafeConventionsCatalogIndexService>()
        .currentIndex()
        .buildRootUrls(catalogUrl)
        .mapNotNull(virtualFileManager::findFileByUrl)
}
