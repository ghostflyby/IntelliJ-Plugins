/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parents
import com.intellij.util.Processor
import com.intellij.util.concurrency.ThreadingAssertions
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue

internal data class TypesafeConventionsKotlinCatalogAccessor(
    val catalogName: String,
    val section: TypesafeConventionsCatalogSection,
    val aliasPath: String,
)

private data class TypesafeConventionsKotlinCatalogSearchTarget(
    val entry: TomlKeyValue,
    val section: TypesafeConventionsCatalogSection,
    val searchWord: String,
    val catalogBuildRoots: List<VirtualFile>,
)

internal class TypesafeConventionsKotlinCatalogReference(
    expression: KtDotQualifiedExpression,
) : PsiReferenceBase<KtDotQualifiedExpression>(expression) {

    @RequiresReadLock(generateAssertion = false)
    override fun resolve(): TomlKeyValue? {
        ThreadingAssertions.assertReadAccess()
        val accessor = element.typesafeConventionsCatalogAccessor() ?: return null
        val tomlFile = findTypesafeConventionsCatalogTomlFile(element, accessor.catalogName) ?: return null
        return findTypesafeConventionsCatalogEntry(tomlFile, accessor.section, accessor.aliasPath)
    }

    @RequiresReadLock
    override fun handleElementRename(newElementName: String): PsiElement =
        element.replaceWithTypesafeConventionsCatalogAccessor(newElementName)
}

internal class TypesafeConventionsKotlinCatalogGotoDeclarationHandler : GotoDeclarationHandler {

    @RequiresReadLock
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        sourceElement ?: return null
        val expression = sourceElement.findTypesafeConventionsCatalogExpression() ?: return null
        val reference = expression.references
            .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
            .singleOrNull()
            ?: return null
        return reference.resolve()?.let { arrayOf(it) }
    }
}

internal class TypesafeConventionsKotlinCatalogUseScopeEnlarger : UseScopeEnlarger() {

    @RequiresReadLock(generateAssertion = false)
    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        ThreadingAssertions.assertReadAccess()
        val keySegment = element as? TomlKeySegment ?: return null
        keySegment.typesafeConventionsKotlinCatalogSearchTarget() ?: return null
        return GlobalSearchScope.projectScope(element.project)
    }
}

internal class TypesafeConventionsKotlinCatalogReferencesSearcher :
    QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>(true) {

    @RequiresReadLock
    @RequiresBackgroundThread
    override fun processQuery(
        queryParameters: ReferencesSearch.SearchParameters,
        consumer: Processor<in PsiReference>,
    ) {
        val keySegment = queryParameters.elementToSearch as? TomlKeySegment ?: return
        val target = keySegment.typesafeConventionsKotlinCatalogSearchTarget() ?: return
        val resultProcessor = CatalogReferenceRequestProcessor(
            target.entry,
            target.section,
            target.catalogBuildRoots,
        )
        queryParameters.optimizer.searchCustom { consumer ->
            PsiSearchHelper.getInstance(keySegment.project).processElementsWithWord(
                { element, offset -> resultProcessor.processTextOccurrence(element, offset, consumer) },
                queryParameters.scopeDeterminedByUser,
                target.searchWord,
                UsageSearchContext.IN_CODE,
                false,
            )
        }
    }

    private class CatalogReferenceRequestProcessor(
        private val searchedEntry: TomlKeyValue,
        private val section: TypesafeConventionsCatalogSection,
        private val catalogBuildRoots: List<VirtualFile>,
    ) : RequestResultProcessor(searchedEntry, section, catalogBuildRoots) {

        @RequiresReadLock(generateAssertion = false)
        override fun processTextOccurrence(
            element: PsiElement,
            offsetInElement: Int,
            consumer: Processor<in PsiReference>,
        ): Boolean {
            ThreadingAssertions.assertReadAccess()
            val occurrence = element as? KtNameReferenceExpression ?: return true
            val virtualFile = occurrence.containingFile.virtualFile ?: return true
            if (catalogBuildRoots.none { VfsUtilCore.isAncestor(it, virtualFile, false) }) {
                return true
            }
            val expression = occurrence.findTypesafeConventionsCatalogExpression() ?: return true
            val reference = TypesafeConventionsKotlinCatalogReference(expression)
            return reference.resolve() !== searchedEntry ||
                    consumer.process(
                        TypesafeConventionsKotlinCatalogUsageReference(expression, searchedEntry, section),
                    )
        }
    }
}

@RequiresReadLock(generateAssertion = false)
private fun TomlKeySegment.typesafeConventionsKotlinCatalogSearchTarget():
        TypesafeConventionsKotlinCatalogSearchTarget? {
    ThreadingAssertions.assertReadAccess()
    val keyValue = parentOfType<TomlKeyValue>(withSelf = false) ?: return null
    val section = findTypesafeConventionsCatalogSection(keyValue) ?: return null
    if (this !in findTypesafeConventionsCatalogAliasSegments(keyValue, section)) {
        return null
    }
    val searchWord = name?.toCatalogSearchWord().orEmpty()
    if (searchWord.isEmpty()) {
        return null
    }
    val catalogFile = keyValue.containingFile as? TomlFile ?: return null
    val catalogBuildRoots = findTypesafeConventionsCatalogBuildRoots(catalogFile)
    if (catalogBuildRoots.isEmpty()) {
        return null
    }
    return TypesafeConventionsKotlinCatalogSearchTarget(
        entry = keyValue,
        section = section,
        searchWord = searchWord,
        catalogBuildRoots = catalogBuildRoots,
    )
}

internal class TypesafeConventionsKotlinCatalogUsageReference(
    expression: KtDotQualifiedExpression,
    private val searchedEntry: TomlKeyValue,
    private val section: TypesafeConventionsCatalogSection,
) : PsiReferenceBase<KtDotQualifiedExpression>(expression) {

    override fun resolve(): TomlKeyValue = searchedEntry

    @RequiresReadLock
    override fun handleElementRename(newElementName: String): PsiElement =
        element.replaceWithTypesafeConventionsCatalogAccessor(newElementName, section)
}

@RequiresReadLock(generateAssertion = false)
internal fun KtDotQualifiedExpression.typesafeConventionsCatalogAccessor():
        TypesafeConventionsKotlinCatalogAccessor? {
    ThreadingAssertions.assertReadAccess()
    val names = catalogNameParts() ?: return null
    if (names.size < 2) {
        return null
    }
    val section = TypesafeConventionsCatalogSection.fromAccessorPrefix(names[1])
        ?: TypesafeConventionsCatalogSection.LIBRARIES
    val aliasParts = names.drop(if (section == TypesafeConventionsCatalogSection.LIBRARIES) 1 else 2)
    if (aliasParts.isEmpty()) {
        return null
    }
    return TypesafeConventionsKotlinCatalogAccessor(
        catalogName = names.first(),
        section = section,
        aliasPath = aliasParts.joinToString("."),
    )
}

internal fun buildTypesafeConventionsKotlinCatalogAccessorText(
    catalogName: String,
    section: TypesafeConventionsCatalogSection,
    aliasName: String,
): String {
    val aliasParts = aliasName.split('.', '-', '_').filter(String::isNotEmpty)
    require(aliasParts.isNotEmpty()) { "Version catalog alias must not be empty" }
    return buildList {
        add(catalogName)
        if (section != TypesafeConventionsCatalogSection.LIBRARIES) {
            add(section.tomlName)
        }
        addAll(aliasParts)
    }.joinToString(".")
}

@RequiresReadLock
internal fun createTypesafeConventionsKotlinCatalogAccessorExpression(
    element: KtDotQualifiedExpression,
    section: TypesafeConventionsCatalogSection,
    aliasName: String,
): KtExpression {
    val catalogName = element.typesafeConventionsCatalogAccessor()?.catalogName
        ?: element.text.substringBefore('.')
    val expressionText = buildTypesafeConventionsKotlinCatalogAccessorText(catalogName, section, aliasName)
    return KtPsiFactory(element.project).createExpression(expressionText)
}

@RequiresReadLock
internal fun KtDotQualifiedExpression.matchesTopmostTypesafeConventionsCatalogReferencePattern(): Boolean =
    hasOnlyNameReferences() && !hasWrappingVersionCatalogExpression()

@RequiresReadLock
private fun KtDotQualifiedExpression.replaceWithTypesafeConventionsCatalogAccessor(
    newElementName: String,
    sectionOverride: TypesafeConventionsCatalogSection? = null,
): PsiElement {
    val accessor = typesafeConventionsCatalogAccessor() ?: return this
    val newExpression = createTypesafeConventionsKotlinCatalogAccessorExpression(
        this,
        sectionOverride ?: accessor.section,
        newElementName,
    )
    return replace(newExpression)
}

@RequiresReadLock
private fun PsiElement.findTypesafeConventionsCatalogExpression(): KtDotQualifiedExpression? =
    parents(withSelf = true)
        .filterIsInstance<KtDotQualifiedExpression>()
        .firstOrNull { it.matchesTopmostTypesafeConventionsCatalogReferencePattern() }

@RequiresReadLock
private fun KtExpression.catalogNameParts(): List<String>? =
    when (this) {
        is KtNameReferenceExpression -> listOf(getReferencedName())
        is KtDotQualifiedExpression -> {
            val receiverParts = receiverExpression.catalogNameParts() ?: return null
            val selectorParts = selectorExpression?.catalogNameParts() ?: return null
            receiverParts + selectorParts
        }

        else -> null
    }

@RequiresReadLock
private fun KtDotQualifiedExpression.hasWrappingVersionCatalogExpression(): Boolean =
    parent is KtDotQualifiedExpression && parent.lastChild is KtNameReferenceExpression

@RequiresReadLock
private fun KtDotQualifiedExpression.hasOnlyNameReferences(): Boolean =
    catalogNameParts() != null

private fun String.toCatalogSearchWord(): String =
    split('.', '-', '_').lastOrNull(String::isNotEmpty).orEmpty()
