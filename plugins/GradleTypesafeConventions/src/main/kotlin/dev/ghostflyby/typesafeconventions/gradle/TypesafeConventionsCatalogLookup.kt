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
import org.toml.lang.psi.*
import org.toml.lang.psi.ext.name

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

internal fun findTypesafeConventionsTomlCatalogKey(tomlFile: TomlFile, declarationPath: String): PsiElement? {
    val sectionPrefixes = listOf("versions.", "bundles.", "plugins.")
    val section: String
    val target: String
    if (sectionPrefixes.none { declarationPath.startsWith(it) }) {
        section = "libraries"
        target = declarationPath
    } else {
        section = declarationPath.substringBefore('.')
        target = declarationPath.substringAfter('.')
    }

    for (element in tomlFile.children) {
        if (element is TomlHeaderOwner) {
            val keyText = element.header.key?.text
            if (keysMatch(keyText, section) && element is TomlKeyValueOwner) {
                return findAlias(element, target)
            }
        }
        if (element is TomlKeyValue) {
            val keyText = element.key.text
            if (keysMatch(keyText, "$section.$target")) {
                return element
            }
            val value = element.value
            if (value is TomlInlineTable && keysMatch(keyText, section)) {
                return findAlias(value, target)
            }
        }
        if (element is TomlTable && keysMatch(element.header.key?.name, section)) {
            return findAlias(element, target)
        }
    }
    return null
}

private fun findAlias(valueOwner: TomlKeyValueOwner, target: String): PsiElement? =
    valueOwner.entries.firstOrNull { keysMatch(it.key.text, target) }

private fun keysMatch(keyText: String?, reference: String): Boolean {
    keyText ?: return false
    if (keyText.length != reference.length) {
        return false
    }
    return keyText.indices.all { index ->
        if (index > 0 && keyText[index - 1].normalizeCatalogKeyChar() == '.') {
            keyText[index].normalizeCatalogKeyChar(ignoreCase = true) == reference[index].normalizeCatalogKeyChar()
        } else {
            keyText[index].normalizeCatalogKeyChar() == reference[index].normalizeCatalogKeyChar()
        }
    }
}

private fun Char.normalizeCatalogKeyChar(ignoreCase: Boolean = false): Char {
    val normalized = if (this == '-' || this == '_') '.' else this
    return if (ignoreCase) normalized.lowercaseChar() else normalized
}
