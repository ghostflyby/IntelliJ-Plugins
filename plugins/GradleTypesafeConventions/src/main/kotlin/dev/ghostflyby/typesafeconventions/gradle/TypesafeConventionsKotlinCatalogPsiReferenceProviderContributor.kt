/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReferenceBase
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor
import org.toml.lang.psi.TomlFile

internal class TypesafeConventionsKotlinCatalogPsiReferenceProviderContributor :
    KotlinPsiReferenceProviderContributor<KtDotQualifiedExpression> {

    override val elementClass: Class<KtDotQualifiedExpression>
        get() = KtDotQualifiedExpression::class.java

    override val referenceProvider: KotlinPsiReferenceProviderContributor.ReferenceProvider<KtDotQualifiedExpression>
        get() = KotlinPsiReferenceProviderContributor.ReferenceProvider { dotExpression ->
            val tomlFile = when {
                !dotExpression.containingFile.name.endsWith(".gradle.kts") -> null
                !dotExpression.matchesTopmostCatalogReferencePattern() -> null
                else -> {
                    val catalogName = dotExpression.text.substringBefore(".")
                    findTypesafeConventionsCatalogTomlFile(dotExpression, catalogName)
                }
            }
            listOfNotNull(
                tomlFile?.let { TypesafeConventionsKotlinCatalogReference(dotExpression, it) },
            )
        }
}

private class TypesafeConventionsKotlinCatalogReference(
    element: KtDotQualifiedExpression,
    private val tomlFile: TomlFile,
) : PsiReferenceBase<KtDotQualifiedExpression>(element) {

    override fun resolve(): PsiElement? {
        val declarationPath = element.text.substringAfter(".")
        return findTypesafeConventionsTomlCatalogKey(tomlFile, declarationPath)
    }
}

private fun KtDotQualifiedExpression.matchesTopmostCatalogReferencePattern(): Boolean =
    hasOnlyNameReferences() && !hasWrappingVersionCatalogExpression()

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
