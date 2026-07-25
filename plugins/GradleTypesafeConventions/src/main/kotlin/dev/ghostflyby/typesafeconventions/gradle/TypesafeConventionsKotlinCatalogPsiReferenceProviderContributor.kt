/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.psi.PsiReference
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor

internal class TypesafeConventionsKotlinCatalogPsiReferenceProviderContributor :
    KotlinPsiReferenceProviderContributor<KtDotQualifiedExpression> {

    override val elementClass: Class<KtDotQualifiedExpression>
        get() = KtDotQualifiedExpression::class.java

    override val referenceProvider: KotlinPsiReferenceProviderContributor.ReferenceProvider<KtDotQualifiedExpression>
        get() = KotlinPsiReferenceProviderContributor.ReferenceProvider(::getReferences)

    @RequiresReadLock
    private fun getReferences(dotExpression: KtDotQualifiedExpression): List<PsiReference> {
        val accessor = dotExpression.typesafeConventionsCatalogAccessor()
        val isTypesafeConventionsCatalog = when {
            !dotExpression.matchesTopmostTypesafeConventionsCatalogReferencePattern() -> false
            accessor == null -> false
            else -> findTypesafeConventionsCatalogTomlFile(dotExpression, accessor.catalogName) != null
        }
        return if (isTypesafeConventionsCatalog) {
            listOf(TypesafeConventionsKotlinCatalogReference(dotExpression))
        } else {
            emptyList()
        }
    }
}
