/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.ModificationTracker
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.ImmutableEntityStorage
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.annotations.TestOnly
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.toml.lang.psi.TomlFile
import java.util.concurrent.atomic.AtomicLong

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
    internal val entries: List<TypesafeConventionsCatalogIndexEntry>,
) {
    private val catalogsByName = entries.groupBy(TypesafeConventionsCatalogIndexEntry::catalogName)
    private val buildRootUrlsByCatalogUrl = entries
        .groupBy(TypesafeConventionsCatalogIndexEntry::catalogUrl)
        .mapValues { (_, catalogs) -> catalogs.mapTo(sortedSetOf()) { it.buildUrl } }

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

    override fun equals(other: Any?): Boolean =
        this === other || other is TypesafeConventionsCatalogIndex &&
                entries == other.entries

    override fun hashCode(): Int = entries.hashCode()

    internal companion object {
        fun create(entries: Collection<TypesafeConventionsCatalogIndexEntry>) =
            TypesafeConventionsCatalogIndex(
                entries = entries
                    .map { entry -> entry.copy(contextRootUrls = entry.contextRootUrls.toSortedSet()) }
                    .distinct()
                    .sortedWith(
                        compareBy(
                            TypesafeConventionsCatalogIndexEntry::catalogName,
                            TypesafeConventionsCatalogIndexEntry::buildUrl,
                            TypesafeConventionsCatalogIndexEntry::catalogUrl,
                        ).thenBy { entry -> entry.contextRootUrls.joinToString("\u0000") },
                    ),
            )
    }
}

@Service(Service.Level.PROJECT)
internal class TypesafeConventionsCatalogIndexService(private val project: Project) : ModificationTracker {
    @Volatile
    private var publishedIndex: TypesafeConventionsCatalogIndex? = null
    private val generation = AtomicLong()

    override fun getModificationCount(): Long = generation.get()

    @RequiresReadLock
    fun currentIndex(): TypesafeConventionsCatalogIndex {
        publishedIndex?.let { return it }

        return synchronized(this) {
            publishedIndex ?: buildCurrentIndex().also { publishedIndex = it }
        }
    }

    fun rebuildAndPublish(forceInvalidate: Boolean = false): Boolean =
        publish(buildCurrentIndex(), forceInvalidate)

    @TestOnly
    internal fun publishForTests(
        index: TypesafeConventionsCatalogIndex,
        forceInvalidate: Boolean = false,
    ): Boolean = publish(index, forceInvalidate)

    private fun publish(index: TypesafeConventionsCatalogIndex, forceInvalidate: Boolean): Boolean =
        synchronized(this) {
            if (!forceInvalidate && publishedIndex == index) {
            return@synchronized false
        }
            publishedIndex = index
        generation.incrementAndGet()
        true
    }

    private fun buildCurrentIndex(): TypesafeConventionsCatalogIndex {
        val snapshot = project.workspaceModel.currentSnapshot
        val enabledBuildUrls = project.service<TypesafeConventionsGradleBuildState>().committedBuildUrls()
        return buildIndex(snapshot, enabledBuildUrls)
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
