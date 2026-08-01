/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.typesafeconventions.gradle

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.search.*
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.*
import com.intellij.util.Processor
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import com.intellij.util.concurrency.annotations.RequiresReadLock
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.references.KotlinPsiReferenceProviderContributor
import org.toml.lang.psi.TomlFile
import org.toml.lang.psi.TomlKeySegment
import org.toml.lang.psi.TomlKeyValue
import java.util.concurrent.ConcurrentHashMap

internal data class TypesafeConventionsKotlinCatalogAccessor(
    val catalogName: String,
    val section: TypesafeConventionsCatalogSection,
    val nameExpressions: List<KtNameReferenceExpression>,
    val aliasSelectorStartIndex: Int,
) {
    val aliasSelectorNames: List<String>
        get() = nameExpressions.drop(aliasSelectorStartIndex).map { it.getReferencedName() }

    val aliasSelectorExpressions: List<KtNameReferenceExpression>
        get() = nameExpressions.drop(aliasSelectorStartIndex)

    val aliasPath: String
        get() = aliasSelectorNames.joinToString(".")
}

internal data class TypesafeConventionsKotlinCatalogSelectorGroup(
    val selectorStartIndex: Int,
    val selectorEndIndex: Int,
    val rangeInElement: TextRange,
    val targetSegment: TomlKeySegment,
)

private data class TypesafeConventionsKotlinCatalogSearchTarget(
    val section: TypesafeConventionsCatalogSection,
    val searchWord: String,
    val catalogBuildRoots: List<VirtualFile>,
)

internal open class TypesafeConventionsKotlinCatalogReference(
    expression: KtDotQualifiedExpression,
    internal val selectorIndex: Int,
    rangeInElement: TextRange,
) : PsiReferenceBase<KtDotQualifiedExpression>(expression, rangeInElement, true) {

    @RequiresReadLock
    @RequiresBackgroundThread
    override fun resolve(): TomlKeySegment? =
        element.typesafeConventionsCatalogSelectorGroups()
            .groupForSelector(selectorIndex)
            ?.targetSegment
            ?.takeIf(PsiElement::isValid)

    @RequiresReadLock
    override fun handleElementRename(newElementName: String): PsiElement {
        val group = element.typesafeConventionsCatalogSelectorGroups()
            .groupForSelector(selectorIndex)
            ?: return element
        return element.replaceTypesafeConventionsCatalogAliasGroup(group, newElementName)
    }
}

internal class TypesafeConventionsKotlinCatalogPsiReferenceProviderContributor :
    KotlinPsiReferenceProviderContributor<KtDotQualifiedExpression> {

    override val elementClass: Class<KtDotQualifiedExpression>
        get() = KtDotQualifiedExpression::class.java

    override val referenceProvider: KotlinPsiReferenceProviderContributor.ReferenceProvider<KtDotQualifiedExpression>
        get() = KotlinPsiReferenceProviderContributor.ReferenceProvider(::getReferences)

    @RequiresReadLock
    @RequiresBackgroundThread
    private fun getReferences(dotExpression: KtDotQualifiedExpression): List<PsiReference> =
        createTypesafeConventionsKotlinCatalogReferences(dotExpression)
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
        val searchSession = queryParameters.optimizer.searchSession
        val processedGroups = synchronized(searchSession) {
            searchSession.getUserData(PROCESSED_CATALOG_SELECTOR_GROUPS_KEY)
                ?: ConcurrentHashMap.newKeySet<ProcessedSelectorGroup>().also { groups ->
                    searchSession.putUserData(PROCESSED_CATALOG_SELECTOR_GROUPS_KEY, groups)
                }
        }
        val resultProcessor = CatalogReferenceRequestProcessor(
            keySegment,
            target.section,
            processedGroups,
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
        private val processedGroups: MutableSet<ProcessedSelectorGroup>,
    ) : RequestResultProcessor(searchedSegment, section) {
        @RequiresReadLock
        override fun processTextOccurrence(
            element: PsiElement,
            offsetInElement: Int,
            consumer: Processor<in PsiReference>,
        ): Boolean {
            val occurrence = element as? KtNameReferenceExpression ?: return true
            val expression = occurrence.findTypesafeConventionsCatalogExpression() ?: return true
            val accessor = expression.typesafeConventionsCatalogAccessor() ?: return true
            val absoluteOffset = occurrence.textRange.startOffset + offsetInElement
            val selectorIndex = accessor.aliasSelectorExpressions.indexOfFirst { selector ->
                selector === occurrence || selector.textRange.containsOffset(absoluteOffset)
            }.takeIf { it >= 0 } ?: return true
            val group = expression.typesafeConventionsCatalogSelectorGroups()
                .groupForSelector(selectorIndex)
                ?: return true
            if (group.targetSegment !== searchedSegment) {
                return true
            }
            val expressionFileUrl = expression.containingFile.virtualFile?.url ?: return true
            if (!processedGroups.add(
                    ProcessedSelectorGroup(
                        expressionFileUrl,
                        expression.textRange.startOffset,
                        group.selectorStartIndex,
                        group.selectorEndIndex,
                    ),
                )
            ) {
                return true
            }
            if (!accessor.resolvesToTypesafeConventionsEntrypoint()) {
                return true
            }
            return consumer.process(
                TypesafeConventionsKotlinCatalogUsageReference(
                    expression,
                    selectorIndex,
                    group.rangeInElement,
                ),
            )
        }
    }
}

private data class ProcessedSelectorGroup(
    val expressionFileUrl: String,
    val expressionStartOffset: Int,
    val selectorStartIndex: Int,
    val selectorEndIndex: Int,
)

@RequiresReadLock
private fun TomlKeySegment.typesafeConventionsKotlinCatalogSearchTarget():
        TypesafeConventionsKotlinCatalogSearchTarget? {
    val keyValue = parentOfType<TomlKeyValue>(withSelf = false) ?: return null
    val alias = findTypesafeConventionsTomlCatalogAlias(keyValue) ?: return null
    if (this !in alias.segments) {
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
        section = alias.section,
        searchWord = searchWord,
        catalogBuildRoots = catalogBuildRoots,
    )
}

internal class TypesafeConventionsKotlinCatalogUsageReference(
    expression: KtDotQualifiedExpression,
    selectorIndex: Int,
    rangeInElement: TextRange,
) : TypesafeConventionsKotlinCatalogReference(expression, selectorIndex, rangeInElement) {
    private val identity = UsageReferenceIdentity(
        fileUrl = expression.containingFile.virtualFile?.url,
        expressionStartOffset = expression.textRange.startOffset,
        rangeInExpression = rangeInElement,
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is TypesafeConventionsKotlinCatalogUsageReference && identity == other.identity

    override fun hashCode(): Int = identity.hashCode()

    private data class UsageReferenceIdentity(
        val fileUrl: String?,
        val expressionStartOffset: Int,
        val rangeInExpression: TextRange,
    )
}

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
    if (!accessor.resolvesToTypesafeConventionsEntrypoint()) {
        return emptyList()
    }
    return accessor.aliasSelectorExpressions.mapIndexed { selectorIndex, selector ->
        TypesafeConventionsKotlinCatalogReference(
            expression,
            selectorIndex,
            expression.relativeRange(selector, selector),
        )
    }
}

@RequiresReadLock
private fun TypesafeConventionsKotlinCatalogAccessor.resolvesToTypesafeConventionsEntrypoint(): Boolean {
    val declaration = nameExpressions.first().mainReference.resolve() as? KtProperty ?: return false
    return declaration.name == catalogName &&
            !declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            declaration.receiverTypeReference.resolvesToGradleProjectType()
}

@RequiresReadLock
private fun KtTypeReference?.resolvesToGradleProjectType(): Boolean {
    val userType = this?.typeElement as? KtUserType ?: return false
    return userType.referenceExpression?.mainReference?.resolve().resolvedQualifiedName() == GRADLE_PROJECT_FQ_NAME
}

@RequiresReadLock
private fun PsiElement?.resolvedQualifiedName(): String? =
    when (this) {
        is PsiClass -> qualifiedName
        is KtClassOrObject -> fqName?.asString()
        is KtTypeAlias -> getTypeReference().resolvesToQualifiedName()
        else -> this?.navigationElement?.takeIf { it !== this }?.resolvedQualifiedName()
    }

@RequiresReadLock
private fun KtTypeReference?.resolvesToQualifiedName(): String? {
    val userType = this?.typeElement as? KtUserType ?: return null
    return userType.referenceExpression?.mainReference?.resolve().resolvedQualifiedName()
}

@RequiresReadLock
@RequiresBackgroundThread
internal fun KtDotQualifiedExpression.typesafeConventionsCatalogSelectorGroups():
        List<TypesafeConventionsKotlinCatalogSelectorGroup> =
    CachedValuesManager.getManager(project).getCachedValue(
        this,
        TYPESAFE_CONVENTIONS_CATALOG_SELECTOR_GROUPS_KEY,
        { createTypesafeConventionsCatalogSelectorGroupsResult() },
        false,
    )

@RequiresReadLock
@RequiresBackgroundThread
private fun KtDotQualifiedExpression.createTypesafeConventionsCatalogSelectorGroupsResult():
        CachedValueProvider.Result<List<TypesafeConventionsKotlinCatalogSelectorGroup>> {
    val accessor = typesafeConventionsCatalogAccessor()
        ?: return CachedValueProvider.Result.create(emptyList(), this)
    val contextUrl = containingFile.originalFile.virtualFile?.url
        ?: containingFile.virtualFile?.url
        ?: return CachedValueProvider.Result.create(emptyList(), this)
    val catalogIndexService = project.service<TypesafeConventionsCatalogIndexService>()
    val catalog = catalogIndexService.currentIndex().findCatalog(contextUrl, accessor.catalogName)
        ?: return CachedValueProvider.Result.create(
            emptyList(),
            this,
            catalogIndexService,
        )
    val virtualFile = VirtualFileManager.getInstance().findFileByUrl(catalog.catalogUrl)
        ?: return CachedValueProvider.Result.create(
            emptyList(),
            this,
            catalogIndexService,
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    val tomlFile = manager.findFile(virtualFile) as? TomlFile
        ?: return CachedValueProvider.Result.create(
            emptyList(),
            this,
            catalogIndexService,
            VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS,
        )
    val alias = typesafeConventionsTomlCatalogAliasIndex(tomlFile)
        .find(accessor.section, accessor.aliasPath)
    val selectorGroups = alias?.segments
        ?.let { segments -> createTypesafeConventionsKotlinCatalogSelectorGroups(accessor, segments) }
        .orEmpty()
    return CachedValueProvider.Result.create(
        selectorGroups,
        this,
        catalogIndexService,
        tomlFile,
    )
}

private fun List<TypesafeConventionsKotlinCatalogSelectorGroup>.groupForSelector(
    selectorIndex: Int,
): TypesafeConventionsKotlinCatalogSelectorGroup? =
    firstOrNull { group -> selectorIndex in group.selectorStartIndex until group.selectorEndIndex }

@RequiresReadLock
internal fun KtDotQualifiedExpression.createTypesafeConventionsKotlinCatalogSelectorGroups(
    accessor: TypesafeConventionsKotlinCatalogAccessor,
    tomlSegments: List<TomlKeySegment>,
): List<TypesafeConventionsKotlinCatalogSelectorGroup> {
    val segmentNames = tomlSegments.map { it.name ?: return emptyList() }
    val selectorExpressions = accessor.aliasSelectorExpressions
    val selectorNames = selectorExpressions.map { it.getReferencedName() }
    var selectorIndex = 0
    val groups = buildList {
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
                TypesafeConventionsKotlinCatalogSelectorGroup(
                    selectorStartIndex = selectorIndex,
                    selectorEndIndex = selectorEndIndex,
                    rangeInElement = range,
                    targetSegment = tomlSegments[segmentIndex],
                ),
            )
            selectorIndex = selectorEndIndex
        }
    }
    return groups.takeIf { selectorIndex == selectorNames.size }.orEmpty()
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

@RequiresReadLock
private fun KtDotQualifiedExpression.createTypesafeConventionsKotlinCatalogAccessorExpression(
    section: TypesafeConventionsCatalogSection,
    aliasName: String,
): KtExpression {
    val catalogName = typesafeConventionsCatalogAccessor()?.catalogName
        ?: text.substringBefore('.')
    val aliasSelectors = aliasName.toCatalogSelectorNames()
    require(aliasSelectors.isNotEmpty()) { "Version catalog alias must not be empty" }
    val expressionText = buildList {
        add(catalogName)
        if (section != TypesafeConventionsCatalogSection.LIBRARIES) {
            add(section.tomlName)
        }
        addAll(aliasSelectors)
    }.joinToString(".")
    return KtPsiFactory(project).createExpression(expressionText)
}

@RequiresReadLock
internal fun KtDotQualifiedExpression.matchesTopmostTypesafeConventionsCatalogReferencePattern(): Boolean =
    hasOnlyNameReferences() && !hasWrappingVersionCatalogExpression()

@RequiresReadLock
internal fun KtDotQualifiedExpression.replaceTypesafeConventionsCatalogAliasGroup(
    group: TypesafeConventionsKotlinCatalogSelectorGroup,
    newElementName: String,
): PsiElement {
    val accessor = typesafeConventionsCatalogAccessor() ?: return this
    val aliasSelectorNames = accessor.aliasSelectorNames.toMutableList()
    if (group.selectorStartIndex !in aliasSelectorNames.indices ||
        group.selectorEndIndex !in 1..aliasSelectorNames.size ||
        group.selectorStartIndex >= group.selectorEndIndex
    ) {
        return this
    }
    val replacementSelectors = newElementName.toCatalogSelectorNames()
    if (replacementSelectors.isEmpty()) {
        return this
    }
    aliasSelectorNames.subList(group.selectorStartIndex, group.selectorEndIndex).clear()
    aliasSelectorNames.addAll(group.selectorStartIndex, replacementSelectors)
    return replace(
        createTypesafeConventionsKotlinCatalogAccessorExpression(
            accessor.section,
            aliasSelectorNames.joinToString("."),
        ),
    )
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

private val TYPESAFE_CONVENTIONS_CATALOG_SELECTOR_GROUPS_KEY =
    Key.create<CachedValue<List<TypesafeConventionsKotlinCatalogSelectorGroup>>>(
        "typesafe.conventions.kotlin.catalog.selector.groups",
    )

private val PROCESSED_CATALOG_SELECTOR_GROUPS_KEY =
    Key.create<MutableSet<ProcessedSelectorGroup>>(
        "typesafe.conventions.kotlin.catalog.processed.selector.groups",
    )

private const val GRADLE_PROJECT_FQ_NAME = "org.gradle.api.Project"
