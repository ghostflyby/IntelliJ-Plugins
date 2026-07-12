/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.toml.lang.psi.TomlFile

@Suppress("UnstableApiUsage")
internal fun findTypesafeConventionsCatalogTomlFile(context: PsiElement, catalogName: String): TomlFile? {
    val contextUrl = context.containingFile?.originalFile?.virtualFile?.url
        ?: context.containingFile?.virtualFile?.url
        ?: return null
    val enabledBuildUrls = context.project.service<TypesafeConventionsGradleBuildState>().enabledBuilds.keys
    if (enabledBuildUrls.isEmpty()) {
        return null
    }
    val catalogUrl = context.project.workspaceModel.currentSnapshot
        .entities<GradleBuildEntity>()
        .filter { it.url.url in enabledBuildUrls }
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

@Suppress("UnstableApiUsage")
private fun GradleBuildEntity.matchLength(contextUrl: String): Int? =
    sequenceOf(url.url)
        .plus(projects.asSequence().map { it.url.url })
        .filter { contextUrl == it || contextUrl.startsWith(it.trimEnd('/') + "/") }
        .maxOfOrNull { it.length }
