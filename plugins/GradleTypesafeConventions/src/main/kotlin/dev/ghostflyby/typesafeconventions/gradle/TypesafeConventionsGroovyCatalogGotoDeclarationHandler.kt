/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.backend.workspace.workspaceModel
import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.storage.entities
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.parentOfType
import org.jetbrains.plugins.gradle.model.projectModel.GradleBuildEntity
import org.jetbrains.plugins.gradle.model.projectModel.gradleModuleEntity
import org.jetbrains.plugins.gradle.model.versionCatalogs.versionCatalogs
import org.jetbrains.plugins.groovy.lang.psi.GrReferenceElement
import org.jetbrains.plugins.groovy.lang.resolve.api.GroovyPropertyBase
import org.toml.lang.psi.TomlFile

internal class TypesafeConventionsGroovyCatalogGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        sourceElement ?: return null
        val reference = sourceElement.parentOfType<GrReferenceElement<*>>(withSelf = true)
            ?: return null
        val catalogName = (reference.resolve() as? GroovyPropertyBase)?.name
            ?: return null
        if (catalogName == "libs") {
            return null
        }

        val tomlFile = findGradleVersionCatalogToml(sourceElement, catalogName)
            ?: return null
        return arrayOf(tomlFile)
    }

    @Suppress("UnstableApiUsage")
    private fun findGradleVersionCatalogToml(context: PsiElement, catalogName: String): TomlFile? {
        val module = ModuleUtilCore.findModuleForPsiElement(context)
            ?: return null
        val moduleEntity = context.project.workspaceModel.currentSnapshot
            .entities<ModuleEntity>()
            .firstOrNull { it.name == module.name }
            ?: return null
        val gradleBuildId = moduleEntity.gradleModuleEntity?.gradleProjectId?.buildId
            ?: return null
        val catalogUrl = context.project.workspaceModel.currentSnapshot
            .entities<GradleBuildEntity>()
            .firstOrNull { it.symbolicId == gradleBuildId }
            ?.versionCatalogs
            ?.firstOrNull { it.name == catalogName }
            ?.url
            ?.url
            ?: return null
        val virtualFile = VirtualFileManager.getInstance().findFileByUrl(catalogUrl)
            ?: return null
        return PsiManager.getInstance(context.project).findFile(virtualFile) as? TomlFile
    }
}
