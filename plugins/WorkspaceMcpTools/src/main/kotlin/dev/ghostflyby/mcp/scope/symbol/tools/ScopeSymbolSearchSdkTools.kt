/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.symbol.tools

import com.intellij.ide.util.gotoByName.*
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.checkCanceled
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.platform.util.progress.reportSequentialProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.*
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import dev.ghostflyby.mcp.Bundle
import dev.ghostflyby.mcp.common.*
import dev.ghostflyby.mcp.server.route.McpCallContext
import dev.ghostflyby.mcp.server.route.project
import dev.ghostflyby.mcp.scope.*
import dev.ghostflyby.mcp.server.tools.toolArgsJson
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.*
import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

// ── Serializable DTOs (moved from old McpToolset) ────────────────

@Schema
@Serializable
internal enum class ScopeSymbolQuickPreset {
    PROJECT_FILES,
    ALL_PLACES,
}

@Schema
@Serializable
internal data class ScopeSymbolSearchStageStatsDto(
    val recallObserved: Int,
    val convertedProcessed: Int,
    val rawCandidates: Int,
    val returnedItems: Int,
    val timedOut: Boolean,
    val providerMode: String,
)

@Schema
@Serializable
internal data class ScopeSymbolSearchWithStageResultDto(
    val result: ScopeSymbolSearchResultDto,
    val stageStats: ScopeSymbolSearchStageStatsDto,
)

@Schema
@Serializable
internal data class ScopeSymbolSearchHealthcheckResultDto(
    val indexReady: Boolean,
    val providerMode: String,
    val scopeDisplayName: String? = null,
    val scopeShape: ScopeShape? = null,
    val diagnostics: List<String> = emptyList(),
)

// ── Tool class (reflective registration) ───────────────────────────

internal class ScopeSymbolSearchTools {
    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scope_search_symbols(
        @Description("Query string to search")
        query: String,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed.")
        allowUiInteractiveScopes: Boolean = false,
        @Description("Maximum number of symbol items to return.")
        maxResultCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether to include library/dependency symbols.")
        includeNonProjectItems: Boolean = true,
        @Description("Whether returned items must have physical file+position.")
        requirePhysicalLocation: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        try {
            if (query.isBlank()) {
                return errorResult("query must not be blank.")
            }
            if (maxResultCount < 1) {
                return errorResult("maxResultCount must be >= 1.")
            }
            if (timeoutMillis < 1) {
                return errorResult("timeoutMillis must be >= 1.")
            }

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

            val result = scopeSymbolSearchImpl(
                project = project,
                query = query,
                scope = scope,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
                maxResultCount = maxResultCount,
                timeoutMillis = timeoutMillis,
                includeNonProjectItems = includeNonProjectItems,
                requirePhysicalLocation = requirePhysicalLocation,
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
            return okResult(toolArgsJson.encodeToString(result))
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid argument.")
        } catch (e: WorkspaceResourceException) {
            return errorResult(e.message ?: "Resource error.")
        }
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scope_search_symbols_quick(
        @Description("Query string to search")
        query: String,
        @Description("Preset scope for quick symbol search.")
        scopePreset: ScopeSymbolQuickPreset = ScopeSymbolQuickPreset.PROJECT_FILES,
        @Description("Maximum number of symbol items to return.")
        maxResultCount: Int = 50,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 20000,
        @Description("Whether returned items must have physical file+position.")
        requirePhysicalLocation: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        try {
            if (query.isBlank()) {
                return errorResult("query must not be blank.")
            }
            if (maxResultCount < 1) {
                return errorResult("maxResultCount must be >= 1.")
            }
            if (timeoutMillis < 1) {
                return errorResult("timeoutMillis must be >= 1.")
            }

            reportActivity(
                Bundle.message(
                    "tool.activity.scope.symbol.search.start",
                    query.length,
                    maxResultCount,
                    timeoutMillis,
                    false,
                    requirePhysicalLocation,
                ),
            )

            val descriptor = buildPresetScopeDescriptor(
                project = project,
                preset = scopePreset.toScopeQuickPreset(),
                allowUiInteractiveScopes = false,
            )
            val includeNonProjectItems = scopePreset.includesNonProjectItems()
            val result = scopeSymbolSearchImpl(
                project = project,
                query = query,
                scope = descriptor,
                allowUiInteractiveScopes = false,
                maxResultCount = maxResultCount,
                timeoutMillis = timeoutMillis,
                includeNonProjectItems = includeNonProjectItems,
                requirePhysicalLocation = requirePhysicalLocation,
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
            return okResult(toolArgsJson.encodeToString(result))
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid argument.")
        }
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scope_search_symbols_with_stage_progress(
        @Description("Query string to search")
        query: String,
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed.")
        allowUiInteractiveScopes: Boolean = false,
        @Description("Maximum number of symbol items to return.")
        maxResultCount: Int = 200,
        @Description("Timeout in milliseconds.")
        timeoutMillis: Int = 30000,
        @Description("Whether to include library/dependency symbols.")
        includeNonProjectItems: Boolean = true,
        @Description("Whether returned items must have physical file+position.")
        requirePhysicalLocation: Boolean = true,
    ): CallToolResult {
        val project = call.project()
        try {
            if (query.isBlank()) {
                return errorResult("query must not be blank.")
            }
            if (maxResultCount < 1) {
                return errorResult("maxResultCount must be >= 1.")
            }
            if (timeoutMillis < 1) {
                return errorResult("timeoutMillis must be >= 1.")
            }

            val healthResult = scopeSymbolSearchHealthcheckImpl(
                project = project,
                scope = scope,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
            )
            val searchResult = scopeSymbolSearchImpl(
                project = project,
                query = query,
                scope = scope,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
                maxResultCount = maxResultCount,
                timeoutMillis = timeoutMillis,
                includeNonProjectItems = includeNonProjectItems,
                requirePhysicalLocation = requirePhysicalLocation,
            )
            val estimatedProcessed = searchResult.items.size +
                    searchResult.diagnostics.count { it.startsWith("Skipped ") }
            val result = ScopeSymbolSearchWithStageResultDto(
                result = searchResult,
                stageStats = ScopeSymbolSearchStageStatsDto(
                    recallObserved = estimatedProcessed,
                    convertedProcessed = estimatedProcessed,
                    rawCandidates = estimatedProcessed,
                    returnedItems = searchResult.items.size,
                    timedOut = searchResult.timedOut,
                    providerMode = healthResult.providerMode,
                ),
            )
            return okResult(toolArgsJson.encodeToString(result))
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid argument.")
        }
    }

    @Schema
    internal suspend fun McpCallContext<CallToolRequest>.scope_search_symbols_healthcheck(
        @Description("Scope program descriptor.")
        scope: ScopeProgramDescriptorDto,
        @Description("Whether UI-interactive scopes are allowed.")
        allowUiInteractiveScopes: Boolean = false,
    ): CallToolResult {
        val project = call.project()
        try {
            reportActivity(
                Bundle.message(
                    "tool.activity.scope.symbol.search.healthcheck",
                    allowUiInteractiveScopes,
                ),
            )
            val result = scopeSymbolSearchHealthcheckImpl(
                project = project,
                scope = scope,
                allowUiInteractiveScopes = allowUiInteractiveScopes,
            )
            return okResult(toolArgsJson.encodeToString(result))
        } catch (e: IllegalArgumentException) {
            return errorResult(e.message ?: "Invalid argument.")
        } catch (e: WorkspaceResourceException) {
            return errorResult(e.message ?: "Resource error.")
        }
    }
}

// ── Implementations (extracted from old McpToolset) ──────────────

private suspend fun scopeSymbolSearchImpl(
    project: Project,
    query: String,
    scope: ScopeProgramDescriptorDto,
    allowUiInteractiveScopes: Boolean,
    maxResultCount: Int,
    timeoutMillis: Int,
    includeNonProjectItems: Boolean,
    requirePhysicalLocation: Boolean,
): ScopeSymbolSearchResultDto {
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

    val modelDisposable = Disposer.newDisposable("ScopeSymbolSearchSdkTools.scope_search_symbols_impl")
    try {
        val model = GotoSymbolModel2(project, modelDisposable)
        val baseViewModel = StaticChooseByNameViewModel(
            project = project,
            model = model,
            maximumListSizeLimit = maxResultCount,
        )
        val provider = ChooseByNameModelEx.getItemProvider(model, null)

        val observedCandidateCount = AtomicInteger(0)
        val processedCandidateCount = AtomicInteger(0)
        val finished = AtomicBoolean(false)
        val probablyHasMore = AtomicBoolean(false)
        val runtimeDiagnostics = mutableListOf<String>()
        var timedOut = false
        var converted = ConversionResult(items = emptyList(), diagnostics = emptyList())

        if (provider !is ChooseByNameInScopeItemProvider) {
            runtimeDiagnostics +=
                "Item provider '${provider.javaClass.name}' is not ChooseByNameInScopeItemProvider; using contributor fallback path."
        }

        coroutineScope {
            val progressJob = launch {
                while (isActive && !finished.get()) {
                    delay(800.milliseconds)
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
                        val collection =
                            coroutineToIndicator { indicator ->
                                runBlockingCancellable {
                                    readAction {
                                        if (provider is ChooseByNameInScopeItemProvider) {
                                            collectRawCandidatesByInScopeProvider(
                                                provider = provider,
                                                baseViewModel = baseViewModel,
                                                parameters = findParameters,
                                                indicator = indicator,
                                                maxResultCount = maxResultCount,
                                            )
                                        } else {
                                            collectRawCandidatesByModelFallback(
                                                model = model,
                                                parameters = findParameters,
                                                indicator = indicator,
                                                maxResultCount = maxResultCount,
                                            )
                                        }
                                    }
                                }
                            }
                        observedCandidateCount.set(collection.observedCount)
                        if (!collection.completed) {
                            probablyHasMore.set(true)
                        }
                        converted = convertCandidates(
                            project = project,
                            model = model,
                            candidates = collection.candidates,
                            postFilterPolicy = effectiveScope.postFilterPolicy,
                            requirePhysicalLocation = requirePhysicalLocation,
                            processedCandidateCount = processedCandidateCount,
                        )
                    }
                    false
                } ?: true
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

        return ScopeSymbolSearchResultDto(
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
    } catch (_: IndexNotReadyException) {
        throw IllegalStateException("Symbol search is temporarily unavailable while indexes are updating. Please retry.")
    } finally {
        Disposer.dispose(modelDisposable)
    }
}


private suspend fun scopeSymbolSearchHealthcheckImpl(
    project: Project,
    scope: ScopeProgramDescriptorDto,
    allowUiInteractiveScopes: Boolean,
): ScopeSymbolSearchHealthcheckResultDto {
    if (DumbService.isDumb(project)) {
        return ScopeSymbolSearchHealthcheckResultDto(
            indexReady = false,
            providerMode = "unknown",
            diagnostics = listOf("Indexing is in progress; retry symbol search after indexing completes."),
        )
    }
    val resolved = ScopeResolverService.getInstance(project).resolveDescriptor(
        project = project,
        descriptor = scope,
        allowUiInteractiveScopes = allowUiInteractiveScopes,
    )
    val modelDisposable = Disposer.newDisposable("ScopeSymbolSearchSdkTools.scope_search_symbols_healthcheck")
    return try {
        val model = GotoSymbolModel2(project, modelDisposable)
        val provider = ChooseByNameModelEx.getItemProvider(model, null)
        val providerMode = if (provider is ChooseByNameInScopeItemProvider) "inScope" else "fallback"
        val diagnostics = buildList {
            addAll(scope.diagnostics)
            addAll(resolved.diagnostics)
            if (providerMode == "fallback") {
                add("Provider '${provider.javaClass.name}' is not ChooseByNameInScopeItemProvider; contributor fallback path will be used.")
            }
        }.distinct()
        ScopeSymbolSearchHealthcheckResultDto(
            indexReady = true,
            providerMode = providerMode,
            scopeDisplayName = resolved.displayName,
            scopeShape = resolved.scopeShape,
            diagnostics = diagnostics,
        )
    } finally {
        Disposer.dispose(modelDisposable)
    }
}

// ── Private helpers ──────────────────────────────────────────────

private const val GLOBAL_AND_LOCAL_UNION_SCOPE_CLASS = "com.intellij.psi.search.GlobalAndLocalUnionScope"
private const val GLOBAL_AND_LOCAL_UNION_GLOBAL_FIELD = "myMyGlobalScope"
private const val GLOBAL_AND_LOCAL_UNION_LOCAL_FIELD = "myLocalScope"

private fun ensureIndicesReady(project: Project) {
    if (DumbService.isDumb(project)) {
        throw IllegalArgumentException("Symbol search is unavailable while indexing is in progress. Please retry after indexing completes.")
    }
}

private fun collectRawCandidatesByInScopeProvider(
    provider: ChooseByNameInScopeItemProvider,
    baseViewModel: ChooseByNameViewModel,
    parameters: FindSymbolParameters,
    indicator: ProgressIndicator,
    maxResultCount: Int,
): RawCollectionResult {
    val candidates = mutableListOf<RawCandidate>()
    val observedCount = AtomicInteger(0)
    val completedByProvider = provider.filterElementsWithWeights(
        baseViewModel,
        parameters,
        indicator,
        Processor { descriptor ->
            indicator.checkCanceled()
            observedCount.incrementAndGet()
            synchronized(candidates) {
                if (candidates.size >= maxResultCount) {
                    return@Processor false
                }
                candidates += RawCandidate(item = descriptor.item, score = descriptor.weight)
                candidates.size < maxResultCount
            }
        },
    )
    val candidateSnapshot = synchronized(candidates) { candidates.toList() }
    return RawCollectionResult(
        candidates = candidateSnapshot,
        observedCount = observedCount.get(),
        completed = completedByProvider && candidateSnapshot.size < maxResultCount,
    )
}

private fun collectRawCandidatesByModelFallback(
    model: GotoSymbolModel2,
    parameters: FindSymbolParameters,
    indicator: ProgressIndicator,
    maxResultCount: Int,
): RawCollectionResult {
    val names = Collections.synchronizedSet(linkedSetOf<String>())
    val candidates = mutableListOf<RawCandidate>()
    var observedCount = 0
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
            observedCount++
            if (candidates.size >= maxResultCount) {
                return RawCollectionResult(
                    candidates = candidates.toList(),
                    observedCount = observedCount,
                    completed = false,
                )
            }
            candidates += RawCandidate(item = element, score = null)
        }
    }

    return RawCollectionResult(
        candidates = candidates.toList(),
        observedCount = observedCount,
        completed = true,
    )
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

    reportSequentialProgress(candidates.size) { reporter ->
        for (candidate in candidates) {
            reporter.itemStep {
                checkCanceled()
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
                        SkipReason.NONE -> {}
                    }
                    return@itemStep
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
                    return@itemStep
                }
                items += item
            }
        }
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
        assertReadAccess()
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
            filePath = relativizePathOrOriginal(project.basePath, virtualFile.path)

            val document = PsiDocumentManager.getInstance(project).getLastCommittedDocument(effectiveFile)

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
        val name = rawName.ifBlank { "<anonymous>" }
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
        isLikelyTypeDeclarationClassName(className) -> ScopeSymbolKind.CLASS
        isLikelyCallableDeclarationClassName(className) -> ScopeSymbolKind.METHOD
        isLikelyFieldDeclarationClassName(className) -> ScopeSymbolKind.FIELD
        else -> ScopeSymbolKind.SYMBOL
    }
}

private fun extractGlobalAndLocalUnionScopeParts(scope: SearchScope): GlobalAndLocalUnionScopeParts? {
    if (scope.javaClass.name != GLOBAL_AND_LOCAL_UNION_SCOPE_CLASS) {
        return null
    }
    val localScope =
        extractFieldValue(scope, GLOBAL_AND_LOCAL_UNION_LOCAL_FIELD) as? LocalSearchScope ?: return null
    val globalScope =
        extractFieldValue(scope, GLOBAL_AND_LOCAL_UNION_GLOBAL_FIELD) as? GlobalSearchScope ?: return null
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

private fun assertReadAccess() {
    if (!ApplicationManager.getApplication().isReadAccessAllowed) {
        throw IllegalStateException("Internal error: symbol read attempted outside read action.")
    }
}

// ── Helper types ─────────────────────────────────────────────────

private data class RawCandidate(
    val item: Any,
    val score: Int?,
)

private data class RawCollectionResult(
    val candidates: List<RawCandidate>,
    val observedCount: Int,
    val completed: Boolean,
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

// ── Quick helpers ────────────────────────────────────────────────

private fun ScopeSymbolQuickPreset.toScopeQuickPreset(): ScopeQuickPreset {
    return when (this) {
        ScopeSymbolQuickPreset.PROJECT_FILES -> ScopeQuickPreset.PROJECT_FILES
        ScopeSymbolQuickPreset.ALL_PLACES -> ScopeQuickPreset.ALL_PLACES
    }
}

private fun ScopeSymbolQuickPreset.includesNonProjectItems(): Boolean {
    return this == ScopeSymbolQuickPreset.ALL_PLACES
}

private fun errorResult(message: String): CallToolResult {
    return CallToolResult(content = listOf(TextContent(text = message)), isError = true)
}

private fun okResult(text: String): CallToolResult {
    return CallToolResult(content = listOf(TextContent(text = text)), isError = false)
}
