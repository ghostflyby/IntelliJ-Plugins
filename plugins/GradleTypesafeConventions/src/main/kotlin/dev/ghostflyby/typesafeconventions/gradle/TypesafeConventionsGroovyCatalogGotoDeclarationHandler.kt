/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.util.parentOfType
import org.jetbrains.plugins.groovy.lang.psi.GrReferenceElement
import org.jetbrains.plugins.groovy.lang.resolve.api.GroovyPropertyBase

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

        val tomlFile = findTypesafeConventionsCatalogTomlFile(sourceElement, catalogName)
            ?: return null
        return arrayOf(tomlFile)
    }
}
