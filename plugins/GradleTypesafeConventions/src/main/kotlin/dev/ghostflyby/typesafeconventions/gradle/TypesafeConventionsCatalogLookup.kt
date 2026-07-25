/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.toml.lang.psi.TomlFile

@RequiresReadLock(generateAssertion = false)
@RequiresBackgroundThread
internal fun findTypesafeConventionsCatalogTomlFile(context: PsiElement, catalogName: String): TomlFile? {
    ThreadingAssertions.assertReadAccess()
    val contextUrl = context.containingFile?.originalFile?.virtualFile?.url
        ?: context.containingFile?.virtualFile?.url
        ?: return null

    @Suppress("UnstableApiUsage")
    val catalogUrl = context.project.enabledTypesafeConventionsGradleBuilds()
        .mapNotNull { build ->
            val matchLength = build.matchLength(contextUrl) ?: return@mapNotNull null
            val catalog = build.versionCatalogs.firstOrNull { it.name == catalogName }
                ?: return@mapNotNull null
            matchLength to catalog.url.url
        }
        .maxByOrNull { it.first }
        ?.second
        ?: return null
    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(catalogUrl)
        ?: return null
    return context.manager.findFile(virtualFile) as? TomlFile
}

@RequiresReadLock(generateAssertion = false)
internal fun findTypesafeConventionsCatalogBuildRoots(catalogFile: TomlFile): List<VirtualFile> {
    ThreadingAssertions.assertReadAccess()
    val catalogUrl = catalogFile.originalFile.virtualFile?.url
        ?: catalogFile.virtualFile?.url
        ?: return emptyList()
    val virtualFileManager = VirtualFileManager.getInstance()
    @Suppress("UnstableApiUsage")
    return catalogFile.project.enabledTypesafeConventionsGradleBuilds()
        .filter { build -> build.versionCatalogs.any { it.url.url == catalogUrl } }
        .mapNotNull { virtualFileManager.findFileByUrl(it.url.url) }
        .distinctBy { it.url }
        .toList()
}

@Suppress("UnstableApiUsage")
@RequiresReadLock
private fun Project.enabledTypesafeConventionsGradleBuilds(): Sequence<GradleBuildEntity> {
    val enabledBuildUrls = service<TypesafeConventionsGradleBuildState>().enabledBuilds.keys
    if (enabledBuildUrls.isEmpty()) {
        return emptySequence()
    }
    return workspaceModel.currentSnapshot
        .entities<GradleBuildEntity>()
        .filter { it.url.url in enabledBuildUrls }
}

@Suppress("UnstableApiUsage")
private fun GradleBuildEntity.matchLength(contextUrl: String): Int? =
    sequenceOf(url.url)
        .plus(projects.asSequence().map { it.url.url })
        .filter { contextUrl == it || contextUrl.startsWith(it.trimEnd('/') + "/") }
        .maxOfOrNull { it.length }
