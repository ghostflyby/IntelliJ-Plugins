/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.scope.symbol

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.util.gotoByName.*
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.mcpFail
import com.intellij.mcpserver.project
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.*
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.reportActivity
import dev.ghostflyby.mcp.scope.*
import kotlinx.coroutines.*
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private const val GLOBAL_AND_LOCAL_UNION_SCOPE_CLASS = "com.intellij.psi.search.GlobalAndLocalUnionScope"
private const val GLOBAL_AND_LOCAL_UNION_GLOBAL_FIELD = "myMyGlobalScope"
private const val GLOBAL_AND_LOCAL_UNION_LOCAL_FIELD = "myLocalScope"

@Suppress("FunctionName")
internal class ScopeSymbolSearchMcpTools : McpToolset {

    @McpTool
    @McpDescription(
        "Search symbols within a resolved scope descriptor. " +
            "Prefer IntelliJ index/contributor search path (Goto Symbol model) and apply post-filtering for LOCAL/MIXED semantics.",
    )
    suspend fun scope_search_symbols(
        @McpDescription("Symbol query string (for example class/method/field name pattern).")
        query: String,
        scope: ScopeProgramDescriptorDto,
        @McpDescription("Whether UI-interactive scopes are allowed during descriptor resolution.")
        allowUiInteractiveScopes: Boolean = false,
        @McpDescription("Maximum number of symbol items to return.")
        maxResultCount: Int = 200,
        @McpDescription("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @McpDescription("Whether to include non-project symbols (libraries/dependencies) when scope allows.")
        includeNonProjectItems: Boolean = true,
        @McpDescription("Whether returned items must have physical file+position location.")
        requirePhysicalLocation: Boolean = true,
    ): ScopeSymbolSearchResultDto {
        if (query.isBlank()) mcpFail("query must not be blank.")
        if (maxResultCount < 1) mcpFail("maxResultCount must be >= 1.")
        if (timeoutMillis < 1) mcpFail("timeoutMillis must be >= 1.")

        reportActivity(
            Bundle.message(
                "tool.activity.scope.symbol.search.start",
                query.length,
                maxResultCount,
                timeoutMillis,
                includeNonProjectItems,
                requirePhysicalLocation,
            ),
        )

        val project = currentCoroutineContext().project
        ensureIndicesReady(project)

        val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
            project = project,
            descriptor = scope,
            allowUiInteractiveScopes = allowUiInteractiveScopes,
        )

        val effectiveScope = buildEffectiveScope(project, resolved.scope)
        val constrainedGlobalScope = if (includeNonProjectItems) {
            effectiveScope.globalScope
        } else {
            effectiveScope.globalScope.intersectWith(ProjectScope.getProjectScope(project))
        }
        val findParameters = FindSymbolParameters.wrap(query, constrainedGlobalScope)

        val modelDisposable = Disposer.newDisposable("ScopeSymbolSearchMcpTools.scope_search_symbols")
        try {
            val model = GotoSymbolModel2(project, modelDisposable)
            val baseViewModel = StaticChooseByNameViewModel(
                project = project,
                model = model,
                maximumListSizeLimit = maxResultCount,
            )
            val provider = ChooseByNameModelEx.getItemProvider(model, null)

            val rawCandidates = Collections.synchronizedList(mutableListOf<RawCandidate>())
            val observedCandidateCount = AtomicInteger(0)
            val processedCandidateCount = AtomicInteger(0)
            val finished = AtomicBoolean(false)
            val probablyHasMore = AtomicBoolean(false)
            val runtimeDiagnostics = mutableListOf<String>()
            var timedOut = false
            var converted = ConversionResult(
                items = emptyList(),
                diagnostics = emptyList(),
            )

            if (provider !is ChooseByNameInScopeItemProvider) {
                runtimeDiagnostics +=
                    "Item provider '${provider.javaClass.name}' is not ChooseByNameInScopeItemProvider; using contributor fallback path."
            }

            coroutineScope {
                val progressJob = launch {
                    while (isActive && !finished.get()) {
                        delay(800)
                        reportActivity(
                            Bundle.message(
                                "tool.activity.scope.symbol.search.progress",
                                observedCandidateCount.get(),
                                processedCandidateCount.get(),
                            ),
                        )
                    }
                }
                try {
                    timedOut = withTimeoutOrNull(timeoutMillis.milliseconds) {
                        withBackgroundProgress(
                            project,
                            Bundle.message("progress.title.scope.symbol.search", query),
                            cancellable = true,
                        ) {
                            coroutineToIndicator { indicator ->
                                val completed = if (provider is ChooseByNameInScopeItemProvider) {
                                    collectRawCandidatesByInScopeProvider(
                                        provider = provider,
                                        baseViewModel = baseViewModel,
                                        parameters = findParameters,
                                        indicator = indicator,
                                        maxResultCount = maxResultCount,
                                        observedCandidateCount = observedCandidateCount,
                                        out = rawCandidates,
                                    )
                                } else {
                                    collectRawCandidatesByModelFallback(
                                        model = model,
                                        parameters = findParameters,
                                        indicator = indicator,
                                        maxResultCount = maxResultCount,
                                        observedCandidateCount = observedCandidateCount,
                                        out = rawCandidates,
                                    )
                                }
                                if (!completed) {
                                    probablyHasMore.set(true)
                                }
                            }
                        }
                        false
                    } ?: true
                    if (!timedOut) {
                        converted = convertCandidates(
                            project = project,
                            model = model,
                            candidates = rawCandidates.toList(),
                            postFilterPolicy = effectiveScope.postFilterPolicy,
                            requirePhysicalLocation = requirePhysicalLocation,
                            processedCandidateCount = processedCandidateCount,
                        )
                    }
                } finally {
                    finished.set(true)
                    progressJob.cancel()
                }
            }

            if (timedOut) {
                runtimeDiagnostics += "Symbol search timed out before completion."
                probablyHasMore.set(true)
            }

            var items = converted.items
                .sortedWith(
                    compareByDescending<ScopeSymbolSearchItemDto> { it.score ?: Int.MIN_VALUE }
                        .thenBy { it.name }
                        .thenBy { it.filePath ?: "" }
                        .thenBy { it.line ?: Int.MAX_VALUE }
                        .thenBy { it.column ?: Int.MAX_VALUE },
                )

            if (items.size > maxResultCount) {
                items = items.take(maxResultCount)
                probablyHasMore.set(true)
            }
            val result = ScopeSymbolSearchResultDto(
                scopeDisplayName = resolved.displayName,
                scopeShape = resolved.scopeShape,
                query = query,
                includeNonProjectItems = includeNonProjectItems,
                requirePhysicalLocation = requirePhysicalLocation,
                items = items,
                probablyHasMoreMatchingEntries = probablyHasMore.get(),
                timedOut = timedOut,
                canceled = false,
                diagnostics = (
                    scope.diagnostics +
                        resolved.diagnostics +
                        effectiveScope.diagnostics +
                        converted.diagnostics +
                        runtimeDiagnostics
                    ).distinct(),
            )
            reportActivity(
                Bundle.message(
                    "tool.activity.scope.symbol.search.finish",
                    result.items.size,
                    result.probablyHasMoreMatchingEntries,
                    result.timedOut,
                    result.diagnostics.size,
                ),
            )
            return result
        } catch (_: IndexNotReadyException) {
            mcpFail("Symbol search is temporarily unavailable while indexes are updating. Please retry.")
        } finally {
            Disposer.dispose(modelDisposable)
        }
    }

    private fun ensureIndicesReady(project: Project) {
        if (DumbService.isDumb(project)) {
            mcpFail("Symbol search is unavailable while indexing is in progress. Please retry after indexing completes.")
        }
    }

    private fun collectRawCandidatesByInScopeProvider(
        provider: ChooseByNameInScopeItemProvider,
        baseViewModel: ChooseByNameViewModel,
        parameters: FindSymbolParameters,
        indicator: ProgressIndicator,
        maxResultCount: Int,
        observedCandidateCount: AtomicInteger,
        out: MutableList<RawCandidate>,
    ): Boolean {
        val completedByProvider = provider.filterElementsWithWeights(
            baseViewModel,
            parameters,
            indicator,
            Processor { descriptor: FoundItemDescriptor<*> ->
                indicator.checkCanceled()
                observedCandidateCount.incrementAndGet()
                synchronized(out) {
                    if (out.size >= maxResultCount) {
                        return@Processor false
                    }
                    out += RawCandidate(item = descriptor.item, score = descriptor.weight)
                    out.size < maxResultCount
                }
            },
        )
        return completedByProvider && out.size < maxResultCount
    }

    private fun collectRawCandidatesByModelFallback(
        model: GotoSymbolModel2,
        parameters: FindSymbolParameters,
        indicator: ProgressIndicator,
        maxResultCount: Int,
        observedCandidateCount: AtomicInteger,
        out: MutableList<RawCandidate>,
    ): Boolean {
        val names = Collections.synchronizedSet(linkedSetOf<String>())
        model.processNames(
            Processor { name ->
                indicator.checkCanceled()
                names += name
                true
            },
            parameters,
        )

        for (name in names) {
            indicator.checkCanceled()
            val elements = model.getElementsByName(name, parameters, indicator)
            for (element in elements) {
                indicator.checkCanceled()
                observedCandidateCount.incrementAndGet()
                synchronized(out) {
                    if (out.size >= maxResultCount) {
                        return false
                    }
                    out += RawCandidate(item = element, score = null)
                }
            }
        }

        return true
    }

    private suspend fun convertCandidates(
        project: Project,
        model: GotoSymbolModel2,
        candidates: List<RawCandidate>,
        postFilterPolicy: PostFilterPolicy,
        requirePhysicalLocation: Boolean,
        processedCandidateCount: AtomicInteger,
    ): ConversionResult {
        val seen = linkedSetOf<String>()
        val items = mutableListOf<ScopeSymbolSearchItemDto>()
        val diagnostics = mutableListOf<String>()
        var skippedUnsupported = 0
        var skippedLocation = 0
        var skippedPostFilter = 0
        var skippedDuplicates = 0

        for (candidate in candidates) {
            ProgressManager.checkCanceled()
            val converted = convertCandidate(
                project = project,
                model = model,
                candidate = candidate,
                postFilterPolicy = postFilterPolicy,
                requirePhysicalLocation = requirePhysicalLocation,
            )
            processedCandidateCount.incrementAndGet()
            val item = converted.item
            if (item == null) {
                when (converted.skipReason) {
                    SkipReason.UNSUPPORTED_ITEM -> skippedUnsupported++
                    SkipReason.LOCATION_UNAVAILABLE -> skippedLocation++
                    SkipReason.POST_FILTER_REJECTED -> skippedPostFilter++
                    SkipReason.NONE -> {
                    }
                }
                continue
            }

            val dedupeKey = listOf(
                item.name,
                item.qualifiedName ?: "",
                item.fileUrl ?: "",
                item.line?.toString() ?: "",
                item.column?.toString() ?: "",
                item.kind.name,
            ).joinToString("|")
            if (!seen.add(dedupeKey)) {
                skippedDuplicates++
                continue
            }
            items += item
        }

        if (skippedUnsupported > 0) {
            diagnostics += "Skipped $skippedUnsupported candidates that were neither NavigationItem nor PsiElement."
        }
        if (skippedLocation > 0) {
            diagnostics += "Skipped $skippedLocation candidates due to missing location data."
        }
        if (skippedPostFilter > 0) {
            diagnostics += "Filtered out $skippedPostFilter candidates outside effective local/mixed post-filter."
        }
        if (skippedDuplicates > 0) {
            diagnostics += "Removed $skippedDuplicates duplicate symbol entries."
        }

        return ConversionResult(
            items = items,
            diagnostics = diagnostics,
        )
    }

    private suspend fun convertCandidate(
        project: Project,
        model: GotoSymbolModel2,
        candidate: RawCandidate,
        postFilterPolicy: PostFilterPolicy,
        requirePhysicalLocation: Boolean,
    ): CandidateConversionResult {
        return readAction {
            val source = candidate.item
            val navigationItem = source as? NavigationItem
            val psiElement = when (source) {
                is PsiElement -> source
                is PsiElementNavigationItem -> source.targetElement
                else -> null
            }

            if (navigationItem == null && psiElement == null) {
                return@readAction CandidateConversionResult(skipReason = SkipReason.UNSUPPORTED_ITEM)
            }

            val effectiveElement = psiElement?.navigationElement ?: psiElement
            val effectiveFile = effectiveElement?.containingFile
            val virtualFile = effectiveFile?.virtualFile

            if (!passesPostFilter(postFilterPolicy, effectiveElement, virtualFile)) {
                return@readAction CandidateConversionResult(skipReason = SkipReason.POST_FILTER_REJECTED)
            }

            var fileUrl: String? = null
            var filePath: String? = null
            var line: Int? = null
            var column: Int? = null

            if (effectiveFile != null && virtualFile != null) {
                fileUrl = virtualFile.url
                filePath = relativizePath(project.basePath, virtualFile.path)

                val document = PsiDocumentManager.getInstance(project).getLastCommittedDocument(effectiveFile)
                    ?: FileDocumentManager.getInstance().getDocument(virtualFile)

                if (document != null) {
                    val textLength = document.textLength
                    if (textLength <= 0) {
                        line = 1
                        column = 1
                    } else {
                        val rawOffset = effectiveElement.textOffset.coerceAtLeast(0)
                        val safeOffset = rawOffset.coerceAtMost(textLength - 1)
                        line = document.getLineNumber(safeOffset) + 1
                        val lineStartOffset = document.getLineStartOffset(line - 1)
                        column = (safeOffset - lineStartOffset) + 1
                    }
                }
            }

            if (requirePhysicalLocation && (fileUrl == null || line == null)) {
                return@readAction CandidateConversionResult(skipReason = SkipReason.LOCATION_UNAVAILABLE)
            }

            val presentation = navigationItem?.presentation
            val rawName = navigationItem?.name
                ?: (effectiveElement as? PsiNamedElement)?.name
                ?: presentation?.presentableText
                ?: source.toString()
            val name = if (rawName.isNotBlank()) rawName else "<anonymous>"
            val qualifiedName = runCatching { model.getFullName(navigationItem ?: source) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            val language = effectiveElement?.language?.id ?: psiElement?.language?.id

            CandidateConversionResult(
                item = ScopeSymbolSearchItemDto(
                    name = name,
                    qualifiedName = qualifiedName,
                    fileUrl = fileUrl,
                    filePath = filePath,
                    line = line,
                    column = column,
                    kind = inferKind(effectiveElement ?: psiElement),
                    language = language,
                    score = candidate.score,
                ),
                skipReason = SkipReason.NONE,
            )
        }
    }

    private fun buildEffectiveScope(project: Project, resolvedScope: SearchScope): EffectiveScope {
        val diagnostics = mutableListOf<String>()
        return when (resolvedScope) {
            is LocalSearchScope -> {
                EffectiveScope(
                    globalScope = GlobalSearchScopeUtil.toGlobalSearchScope(resolvedScope, project),
                    postFilterPolicy = PostFilterPolicy.LocalOnly(resolvedScope),
                    diagnostics = listOf("Resolved LOCAL scope uses global recall plus element-level local post-filter."),
                )
            }

            is GlobalSearchScope -> {
                val unionParts = extractGlobalAndLocalUnionScopeParts(resolvedScope)
                if (unionParts != null) {
                    diagnostics +=
                        "Resolved scope '${resolvedScope.javaClass.name}' contains local fragments; applying global-or-local post-filter."
                    EffectiveScope(
                        globalScope = resolvedScope,
                        postFilterPolicy = PostFilterPolicy.GlobalOrLocal(
                            globalScope = unionParts.globalScope,
                            localScope = unionParts.localScope,
                        ),
                        diagnostics = diagnostics,
                    )
                } else {
                    EffectiveScope(
                        globalScope = resolvedScope,
                        postFilterPolicy = PostFilterPolicy.None,
                        diagnostics = diagnostics,
                    )
                }
            }

            else -> {
                val reflectedLocal = extractLocalScopeField(resolvedScope)
                if (reflectedLocal != null) {
                    diagnostics +=
                        "Resolved non-global scope '${resolvedScope.javaClass.name}' exposes LocalSearchScope via reflection; " +
                            "using global recall plus local post-filter."
                    EffectiveScope(
                        globalScope = GlobalSearchScopeUtil.toGlobalSearchScope(reflectedLocal, project),
                        postFilterPolicy = PostFilterPolicy.LocalOnly(reflectedLocal),
                        diagnostics = diagnostics,
                    )
                } else {
                    diagnostics +=
                        "Scope type '${resolvedScope.javaClass.name}' cannot be converted losslessly to GlobalSearchScope; " +
                            "using broad global recall with generic post-filter."
                    EffectiveScope(
                        globalScope = ProjectScope.getAllScope(project),
                        postFilterPolicy = PostFilterPolicy.Generic(resolvedScope),
                        diagnostics = diagnostics,
                    )
                }
            }
        }
    }

    private fun passesPostFilter(
        postFilterPolicy: PostFilterPolicy,
        element: PsiElement?,
        file: VirtualFile?,
    ): Boolean {
        return when (postFilterPolicy) {
            PostFilterPolicy.None -> true
            is PostFilterPolicy.LocalOnly -> {
                element?.let { PsiSearchScopeUtil.isInScope(postFilterPolicy.localScope, it) }
                    ?: (file != null && postFilterPolicy.localScope.isInScope(file))
            }

            is PostFilterPolicy.GlobalOrLocal -> {
                val inGlobal = file?.let(postFilterPolicy.globalScope::contains) ?: false
                inGlobal || element?.let { PsiSearchScopeUtil.isInScope(postFilterPolicy.localScope, it) } == true ||
                    (file != null && postFilterPolicy.localScope.isInScope(file))
            }

            is PostFilterPolicy.Generic -> {
                val inByElement = element?.let {
                    runCatching { PsiSearchScopeUtil.isInScope(postFilterPolicy.scope, it) }.getOrNull()
                }
                inByElement ?: (file != null && postFilterPolicy.scope.contains(file))
            }
        }
    }

    private fun inferKind(element: PsiElement?): ScopeSymbolKind {
        if (element == null) return ScopeSymbolKind.UNKNOWN
        val className = element.javaClass.simpleName
        return when {
            className.contains("Class", ignoreCase = true) ||
                className.contains("Interface", ignoreCase = true) ||
                className.contains("Enum", ignoreCase = true) ||
                className.contains("Record", ignoreCase = true) ||
                className.contains("Object", ignoreCase = true) ||
                className.contains("TypeAlias", ignoreCase = true) ||
                className.contains("Struct", ignoreCase = true) ||
                className.contains("Trait", ignoreCase = true) ||
                className.contains("TypeDef", ignoreCase = true) ->
                ScopeSymbolKind.CLASS

            className.contains("Method", ignoreCase = true) ||
                className.contains("Function", ignoreCase = true) ||
                className.contains("Callable", ignoreCase = true) ||
                className.contains("Constructor", ignoreCase = true) ||
                className.contains("Ctor", ignoreCase = true) ->
                ScopeSymbolKind.METHOD

            className.contains("Field", ignoreCase = true) ||
                className.contains("Property", ignoreCase = true) ||
                className.contains("Variable", ignoreCase = true) ->
                ScopeSymbolKind.FIELD

            else -> ScopeSymbolKind.SYMBOL
        }
    }

    private fun extractGlobalAndLocalUnionScopeParts(scope: SearchScope): GlobalAndLocalUnionScopeParts? {
        if (scope.javaClass.name != GLOBAL_AND_LOCAL_UNION_SCOPE_CLASS) {
            return null
        }
        val localScope = extractFieldValue(scope, GLOBAL_AND_LOCAL_UNION_LOCAL_FIELD) as? LocalSearchScope ?: return null
        val globalScope = extractFieldValue(scope, GLOBAL_AND_LOCAL_UNION_GLOBAL_FIELD) as? GlobalSearchScope ?: return null
        return GlobalAndLocalUnionScopeParts(globalScope = globalScope, localScope = localScope)
    }

    private fun extractLocalScopeField(scope: SearchScope): LocalSearchScope? {
        var cursor: Class<*>? = scope.javaClass
        while (cursor != null) {
            cursor.declaredFields.forEach { field ->
                if (!LocalSearchScope::class.java.isAssignableFrom(field.type)) return@forEach
                runCatching {
                    field.isAccessible = true
                    field.get(scope) as? LocalSearchScope
                }.getOrNull()?.let { return it }
            }
            cursor = cursor.superclass
        }
        return null
    }

    private fun extractFieldValue(receiver: Any, fieldName: String): Any? {
        var cursor: Class<*>? = receiver.javaClass
        while (cursor != null) {
            val field = runCatching { cursor.getDeclaredField(fieldName) }.getOrNull()
            if (field != null) {
                return runCatching {
                    field.isAccessible = true
                    field.get(receiver)
                }.getOrNull()
            }
            cursor = cursor.superclass
        }
        return null
    }

    private fun relativizePath(projectBasePath: String?, rawPath: String): String {
        if (projectBasePath.isNullOrBlank()) {
            return rawPath
        }
        return runCatching {
            Path.of(projectBasePath).relativize(Path.of(rawPath)).toString().replace('\\', '/')
        }.getOrDefault(rawPath)
    }

    private data class RawCandidate(
        val item: Any,
        val score: Int?,
    )

    private data class ConversionResult(
        val items: List<ScopeSymbolSearchItemDto>,
        val diagnostics: List<String>,
    )

    private data class CandidateConversionResult(
        val item: ScopeSymbolSearchItemDto? = null,
        val skipReason: SkipReason = SkipReason.NONE,
    )

    private enum class SkipReason {
        NONE,
        UNSUPPORTED_ITEM,
        LOCATION_UNAVAILABLE,
        POST_FILTER_REJECTED,
    }

    private sealed interface PostFilterPolicy {
        data object None : PostFilterPolicy

        data class LocalOnly(
            val localScope: LocalSearchScope,
        ) : PostFilterPolicy

        data class GlobalOrLocal(
            val globalScope: GlobalSearchScope,
            val localScope: LocalSearchScope,
        ) : PostFilterPolicy

        data class Generic(
            val scope: SearchScope,
        ) : PostFilterPolicy
    }

    private data class EffectiveScope(
        val globalScope: GlobalSearchScope,
        val postFilterPolicy: PostFilterPolicy,
        val diagnostics: List<String>,
    )

    private data class GlobalAndLocalUnionScopeParts(
        val globalScope: GlobalSearchScope,
        val localScope: LocalSearchScope,
    )

    private class StaticChooseByNameViewModel(
        private val project: Project,
        private val model: ChooseByNameModel,
        private val maximumListSizeLimit: Int,
    ) : ChooseByNameViewModel {
        override fun getProject(): Project = project

        override fun getModel(): ChooseByNameModel = model

        override fun isSearchInAnyPlace(): Boolean = true

        override fun transformPattern(pattern: String): String = pattern

        override fun canShowListForEmptyPattern(): Boolean = false

        override fun getMaximumListSizeLimit(): Int = maximumListSizeLimit
    }
}
