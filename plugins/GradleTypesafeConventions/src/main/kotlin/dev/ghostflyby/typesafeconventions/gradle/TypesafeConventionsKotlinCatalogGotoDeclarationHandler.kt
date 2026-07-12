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
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression

internal class TypesafeConventionsKotlinCatalogGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val expression = findTopmostVersionCatalogExpression(sourceElement)
            ?: return null
        if (!expression.containingFile.name.endsWith(".gradle.kts")) {
            return null
        }
        val catalogName = expression.text.substringBefore(".")
        val tomlFile = findTypesafeConventionsCatalogTomlFile(expression, catalogName)
            ?: return null
        val target = findTypesafeConventionsTomlCatalogKey(tomlFile, expression.text.substringAfter("."))
            ?: tomlFile
        return arrayOf(target)
    }
}

private fun findTopmostVersionCatalogExpression(element: PsiElement?): KtDotQualifiedExpression? {
    var expression = element?.parentOfType<KtDotQualifiedExpression>()
        ?: return null
    while (expression.hasWrappingVersionCatalogExpression()) {
        expression = expression.parent as KtDotQualifiedExpression
    }
    return expression.takeIf { it.hasOnlyNameReferences() }
}

private fun KtDotQualifiedExpression.hasWrappingVersionCatalogExpression(): Boolean =
    parent is KtDotQualifiedExpression && parent.lastChild is KtNameReferenceExpression

private fun KtDotQualifiedExpression.hasOnlyNameReferences(): Boolean =
    children.all {
        when (it) {
            is KtNameReferenceExpression -> true
            is KtDotQualifiedExpression -> it.hasOnlyNameReferences()
            else -> false
        }
    }
