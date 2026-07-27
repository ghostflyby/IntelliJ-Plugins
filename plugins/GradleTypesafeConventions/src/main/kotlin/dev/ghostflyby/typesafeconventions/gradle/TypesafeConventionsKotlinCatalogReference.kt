/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.parentOfType
import com.intellij.psi.util.parents
import com.intellij.util.Processor
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.psi.*
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue

internal data class TypesafeConventionsKotlinCatalogAccessor(
    val catalogName: String,
    val section: TypesafeConventionsCatalogSection,
    val nameExpressions: List<KtNameReferenceExpression>,
    val aliasSelectorStartIndex: Int,
) {
    val aliasSelectorNames: List<String>
        get() = nameExpressions.drop(aliasSelectorStartIndex).map { it.getReferencedName() }

    val aliasPath: String
        get() = aliasSelectorNames.joinToString(".")
}

internal data class TypesafeConventionsKotlinCatalogReferenceContext(
    val catalogName: String,
    val section: TypesafeConventionsCatalogSection,
    val aliasSegments: List<String>,
    val targetSegmentIndex: Int,
    val selectorStartIndex: Int,
    val selectorEndIndex: Int,
    val catalogUrl: String?,
    val rangeInElement: TextRange,
)

private data class TypesafeConventionsKotlinCatalogSearchTarget(
    val segment: TomlKeySegment,
    val section: TypesafeConventionsCatalogSection,
    val searchWord: String,
    val catalogBuildRoots: List<VirtualFile>,
)

internal open class TypesafeConventionsKotlinCatalogReference(
    expression: KtDotQualifiedExpression,
    internal val context: TypesafeConventionsKotlinCatalogReferenceContext,
    private val resolvedTarget: TomlKeySegment? = null,
) : PsiReferenceBase<KtDotQualifiedExpression>(expression, context.rangeInElement) {

    @RequiresReadLock
    @RequiresBackgroundThread
    override fun resolve(): TomlKeySegment? {
        resolvedTarget?.takeIf(PsiElement::isValid)?.let { return it }
        val tomlFile = findTypesafeConventionsCatalogTomlFile(element, context.catalogName) ?: return null
        if (context.catalogUrl != null && tomlFile.virtualFile?.url != context.catalogUrl) {
            return null
        }
        val entry = findTypesafeConventionsCatalogEntry(
            tomlFile,
            context.section,
            context.aliasSegments.joinToString("."),
        ) ?: return null
        return typesafeConventionsTomlCatalogAliasIndex(tomlFile)
            .find(entry, context.section)
            ?.segments
            ?.getOrNull(context.targetSegmentIndex)
    }

    @RequiresReadLock
    override fun handleElementRename(newElementName: String): PsiElement =
        element.replaceTypesafeConventionsCatalogAliasSegment(context, newElementName)
}

internal class TypesafeConventionsKotlinCatalogGotoDeclarationHandler : GotoDeclarationHandler {

    @RequiresReadLock
    @RequiresBackgroundThread
    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        sourceElement ?: return null
        val expression = sourceElement.findTypesafeConventionsCatalogExpression() ?: return null
        val relativeOffset = offset - expression.textRange.startOffset
        val reference = expression.references
            .filterIsInstance<TypesafeConventionsKotlinCatalogReference>()
            .firstOrNull { it.rangeInElement.containsOffset(relativeOffset) }
            ?: return null
        return reference.resolve()?.let { arrayOf(it) }
    }
}

internal class TypesafeConventionsKotlinCatalogUseScopeEnlarger : UseScopeEnlarger() {

    @RequiresReadLock
    override fun getAdditionalUseScope(element: PsiElement): SearchScope? {
        val keySegment = element as? TomlKeySegment ?: return null
        val target = keySegment.typesafeConventionsKotlinCatalogSearchTarget() ?: return null
        return typesafeConventionsCatalogBuildRootsSearchScope(element.project, target.catalogBuildRoots)
    }
}

internal fun typesafeConventionsCatalogBuildRootsSearchScope(
    project: Project,
    buildRoots: List<VirtualFile>,
): GlobalSearchScope = TypesafeConventionsCatalogBuildRootsSearchScope(project, buildRoots)

private class TypesafeConventionsCatalogBuildRootsSearchScope(
    project: Project,
    private val buildRoots: List<VirtualFile>,
) : GlobalSearchScope(project) {
    override fun contains(file: VirtualFile): Boolean =
        buildRoots.any { root ->
            file == root || file.path.startsWith(root.path.trimEnd('/') + "/")
        }

    override fun isSearchInModuleContent(aModule: Module): Boolean = true

    override fun isSearchInLibraries(): Boolean = false

    override fun toString(): String = "Typesafe conventions catalog build roots: $buildRoots"
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
            target.segment,
            target.section,
        )
        val buildScope = typesafeConventionsCatalogBuildRootsSearchScope(
            keySegment.project,
            target.catalogBuildRoots,
        )
        val searchScope = queryParameters.scopeDeterminedByUser.intersectWith(buildScope)
        queryParameters.optimizer.searchWord(
            target.searchWord,
            searchScope,
            UsageSearchContext.IN_CODE,
            false,
            keySegment,
            resultProcessor,
        )
    }

    private class CatalogReferenceRequestProcessor(
        private val searchedSegment: TomlKeySegment,
        section: TypesafeConventionsCatalogSection,
    ) : RequestResultProcessor(searchedSegment, section) {

        @RequiresReadLock
        override fun processTextOccurrence(
            element: PsiElement,
            offsetInElement: Int,
            consumer: Processor<in PsiReference>,
        ): Boolean {
            val occurrence = element as? KtNameReferenceExpression ?: return true
            val expression = occurrence.findTypesafeConventionsCatalogExpression() ?: return true
            val reference = createTypesafeConventionsKotlinCatalogReferences(expression)
                .firstOrNull { it.resolve() === searchedSegment }
                ?: return true
            return consumer.process(
                TypesafeConventionsKotlinCatalogUsageReference(
                    expression,
                    searchedSegment,
                    reference.context,
                ),
            )
        }
    }
}

@RequiresReadLock
private fun TomlKeySegment.typesafeConventionsKotlinCatalogSearchTarget():
        TypesafeConventionsKotlinCatalogSearchTarget? {
    val keyValue = parentOfType<TomlKeyValue>(withSelf = false) ?: return null
    val section = findTypesafeConventionsCatalogSection(keyValue) ?: return null
    val aliasSegments = findTypesafeConventionsCatalogAliasSegments(keyValue, section)
    if (this !in aliasSegments) {
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
        segment = this,
        section = section,
        searchWord = searchWord,
        catalogBuildRoots = catalogBuildRoots,
    )
}

internal class TypesafeConventionsKotlinCatalogUsageReference(
    expression: KtDotQualifiedExpression,
    searchedSegment: TomlKeySegment,
    context: TypesafeConventionsKotlinCatalogReferenceContext,
) : TypesafeConventionsKotlinCatalogReference(expression, context, searchedSegment)

@RequiresReadLock
internal fun KtDotQualifiedExpression.typesafeConventionsCatalogAccessor():
        TypesafeConventionsKotlinCatalogAccessor? {
    val nameExpressions = catalogNameExpressions() ?: return null
    val names = nameExpressions.map { it.getReferencedName() }
    if (names.size < 2) {
        return null
    }
    val section = TypesafeConventionsCatalogSection.fromAccessorPrefix(names[1])
        ?: TypesafeConventionsCatalogSection.LIBRARIES
    val aliasSelectorStartIndex = if (section == TypesafeConventionsCatalogSection.LIBRARIES) 1 else 2
    if (aliasSelectorStartIndex >= names.size) {
        return null
    }
    return TypesafeConventionsKotlinCatalogAccessor(
        catalogName = names.first(),
        section = section,
        nameExpressions = nameExpressions,
        aliasSelectorStartIndex = aliasSelectorStartIndex,
    )
}

@RequiresReadLock
@RequiresBackgroundThread
internal fun createTypesafeConventionsKotlinCatalogReferences(
    expression: KtDotQualifiedExpression,
): List<TypesafeConventionsKotlinCatalogReference> {
    if (!expression.matchesTopmostTypesafeConventionsCatalogReferencePattern()) {
        return emptyList()
    }
    val accessor = expression.typesafeConventionsCatalogAccessor() ?: return emptyList()
    val tomlFile = findTypesafeConventionsCatalogTomlFile(expression, accessor.catalogName) ?: return emptyList()
    val entry = findTypesafeConventionsCatalogEntry(tomlFile, accessor.section, accessor.aliasPath)
        ?: return emptyList()
    val aliasSegments = typesafeConventionsTomlCatalogAliasIndex(tomlFile)
        .find(entry, accessor.section)
        ?.segments
        .orEmpty()
    if (aliasSegments.isEmpty() || !accessor.resolvesToTypesafeConventionsEntrypoint()) {
        return emptyList()
    }
    val contexts = expression.createTypesafeConventionsCatalogReferenceContexts(
        accessor,
        aliasSegments,
        tomlFile.originalFile.virtualFile?.url ?: tomlFile.virtualFile?.url,
    )
    return contexts.mapIndexed { index, context ->
        TypesafeConventionsKotlinCatalogReference(expression, context, aliasSegments[index])
    }
}

@RequiresReadLock
private fun TypesafeConventionsKotlinCatalogAccessor.resolvesToTypesafeConventionsEntrypoint(): Boolean {
    val declaration = nameExpressions.first().mainReference.resolve() as? KtProperty ?: return false
    val generatedFile = declaration.containingKtFile.virtualFile ?: return false
    val normalizedPath = generatedFile.path.replace('\\', '/')
    return declaration.name == catalogName &&
            declaration.receiverTypeReference?.text?.substringAfterLast('.') == "Project" &&
            generatedFile.name.startsWith("EntrypointFor") &&
            normalizedPath.contains("/build/generated-sources/typesafe-conventions/kotlin/")
}

@RequiresReadLock
internal fun KtDotQualifiedExpression.createTypesafeConventionsCatalogReferenceContexts(
    accessor: TypesafeConventionsKotlinCatalogAccessor,
    tomlSegments: List<TomlKeySegment>,
    catalogUrl: String?,
): List<TypesafeConventionsKotlinCatalogReferenceContext> {
    val segmentNames = tomlSegments.map { it.name ?: return emptyList() }
    val selectorExpressions = accessor.nameExpressions.drop(accessor.aliasSelectorStartIndex)
    val selectorNames = selectorExpressions.map { it.getReferencedName() }
    var selectorIndex = 0
    val contexts = buildList {
        for ((segmentIndex, segmentName) in segmentNames.withIndex()) {
            val selectorEndIndex = (selectorIndex + 1..selectorNames.size).firstOrNull { candidateEnd ->
                typesafeConventionsCatalogKeysMatch(
                    segmentName,
                    selectorNames.subList(selectorIndex, candidateEnd).joinToString("."),
                )
            } ?: return emptyList()
            val range = relativeRange(
                selectorExpressions[selectorIndex],
                selectorExpressions[selectorEndIndex - 1],
            )
            add(
                TypesafeConventionsKotlinCatalogReferenceContext(
                    catalogName = accessor.catalogName,
                    section = accessor.section,
                    aliasSegments = segmentNames,
                    targetSegmentIndex = segmentIndex,
                    selectorStartIndex = selectorIndex,
                    selectorEndIndex = selectorEndIndex,
                    catalogUrl = catalogUrl,
                    rangeInElement = range,
                ),
            )
            selectorIndex = selectorEndIndex
        }
    }
    return contexts.takeIf { selectorIndex == selectorNames.size }.orEmpty()
}

private fun KtDotQualifiedExpression.relativeRange(
    first: KtNameReferenceExpression,
    last: KtNameReferenceExpression,
): TextRange {
    val expressionStart = textRange.startOffset
    return TextRange(
        first.textRange.startOffset - expressionStart,
        last.textRange.endOffset - expressionStart,
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
private fun KtDotQualifiedExpression.replaceTypesafeConventionsCatalogAliasSegment(
    context: TypesafeConventionsKotlinCatalogReferenceContext,
    newElementName: String,
): PsiElement {
    val accessor = typesafeConventionsCatalogAccessor() ?: return this
    val aliasSelectorNames = accessor.aliasSelectorNames.toMutableList()
    if (context.selectorStartIndex !in aliasSelectorNames.indices ||
        context.selectorEndIndex !in 1..aliasSelectorNames.size ||
        context.selectorStartIndex >= context.selectorEndIndex
    ) {
        return this
    }
    val replacementSelectors = newElementName.toCatalogSelectorNames()
    if (replacementSelectors.isEmpty()) {
        return this
    }
    aliasSelectorNames.subList(context.selectorStartIndex, context.selectorEndIndex).clear()
    aliasSelectorNames.addAll(context.selectorStartIndex, replacementSelectors)
    val newExpressionText = buildTypesafeConventionsKotlinCatalogAccessorText(
        accessor.catalogName,
        accessor.section,
        aliasSelectorNames.joinToString("."),
    )
    val newExpression = KtPsiFactory(project).createExpression(newExpressionText)
    return replace(newExpression)
}

@RequiresReadLock
private fun PsiElement.findTypesafeConventionsCatalogExpression(): KtDotQualifiedExpression? =
    parents(withSelf = true)
        .filterIsInstance<KtDotQualifiedExpression>()
        .firstOrNull { it.matchesTopmostTypesafeConventionsCatalogReferencePattern() }

@RequiresReadLock
private fun KtExpression.catalogNameExpressions(): List<KtNameReferenceExpression>? =
    when (this) {
        is KtNameReferenceExpression -> listOf(this)
        is KtDotQualifiedExpression -> {
            val receiverParts = receiverExpression.catalogNameExpressions() ?: return null
            val selectorParts = selectorExpression?.catalogNameExpressions() ?: return null
            receiverParts + selectorParts
        }

        else -> null
    }

@RequiresReadLock
private fun KtDotQualifiedExpression.hasWrappingVersionCatalogExpression(): Boolean =
    parent is KtDotQualifiedExpression && parent.lastChild is KtNameReferenceExpression

@RequiresReadLock
private fun KtDotQualifiedExpression.hasOnlyNameReferences(): Boolean =
    catalogNameExpressions() != null

private fun String.toCatalogSearchWord(): String =
    toCatalogSelectorNames().lastOrNull().orEmpty()

private fun String.toCatalogSelectorNames(): List<String> =
    split('.', '-', '_').filter(String::isNotEmpty)
