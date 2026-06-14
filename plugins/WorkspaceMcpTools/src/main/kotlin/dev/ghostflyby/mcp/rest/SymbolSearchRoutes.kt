/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.ide.util.gotoByName.*
import com.intellij.navigation.NavigationItem
import com.intellij.navigation.PsiElementNavigationItem
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.ProjectScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import dev.ghostflyby.mcp.common.relativizePathOrOriginal
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private const val DefaultSymbolLimit: Int = 50
private const val MaxSymbolLimit: Int = 200
private const val DefaultSymbolTimeoutMillis: Int = 20_000

@Serializable
private data class SymbolSearchItem(
    val name: String,
    val qualifiedName: String? = null,
    val fileUrl: String,
    val encodedFileUrl: String,
    val filePath: String,
    val line: Int,
    val column: Int,
    val kind: String,
    val language: String? = null,
    val score: Int? = null,
)

@Serializable
private data class SymbolSearchResponse(
    val query: String,
    val libraries: Boolean = false,
    val kind: String? = null,
    val limit: Int = DefaultSymbolLimit,
    val timeoutMillis: Int = DefaultSymbolTimeoutMillis,
    val count: Int = 0,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val items: List<SymbolSearchItem> = emptyList(),
    val diagnostics: List<String> = emptyList(),
) : TextBody {
    override fun renderTextBody(): String = buildString {
        appendLine("---")
        appendLine("query: ${yamlScalar(query)}")
        appendLine("libraries: $libraries")
        kind?.let { appendLine("kind: $it") }
        appendLine("limit: $limit")
        appendLine("timeoutMillis: $timeoutMillis")
        appendLine("count: $count")
        appendLine("truncated: $truncated")
        appendLine("timedOut: $timedOut")
        appendLine("---")
        if (items.isEmpty()) {
            appendLine("No symbols")
            if (diagnostics.isNotEmpty()) {
                appendLine("## Diagnostics")
                diagnostics.forEach { appendLine("- $it") }
            }
            return@buildString
        }
        appendLine("## Symbols")
        appendLine("| name | kind | path | encodedFileUrl | line | qualifiedName |")
        appendLine("| --- | --- | --- | --- | ---: | --- |")
        items.forEach { item ->
            val fileReference = markdownFileReference(
                filePath = item.filePath,
                fileUrl = item.fileUrl,
                encodedFileUrl = item.encodedFileUrl,
            )
            appendLine(
                "| ${markdownCell(item.name)} | ${markdownCell(item.kind)} | " +
                        "${markdownCell(fileReference.path)} | ${markdownCell(fileReference.encodedFileUrl)} | " +
                        "${item.line} | ${markdownCell(item.qualifiedName.orEmpty())} |",
            )
        }
        if (diagnostics.isNotEmpty()) {
            appendLine("## Diagnostics")
            diagnostics.forEach { appendLine("- $it") }
        }
    }
}

private data class SymbolSearchOptions(
    val query: String,
    val libraries: Boolean,
    val kind: String?,
    val limit: Int,
    val timeoutMillis: Int,
)

internal fun Route.searchSymbolRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    get<Api.SearchSymbolsEntry> { resource ->
        val query = resource.query.trim()
        if (query.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, RestError("query must not be blank."))
            return@get
        }
        val kind = parseSymbolKind(resource.kind)
        if (resource.kind != null && kind == null) {
            call.respond(HttpStatusCode.BadRequest, RestError("kind '${resource.kind}' is not supported."))
            return@get
        }
        if (resource.limit < 1) {
            call.respond(HttpStatusCode.BadRequest, RestError("limit must be greater than 0."))
            return@get
        }
        if (resource.timeoutMillis < 1) {
            call.respond(HttpStatusCode.BadRequest, RestError("timeoutMillis must be greater than 0."))
            return@get
        }

        val record = when (val session = sessions.resolveRecord(call.request.headers[RestSessionHeader])) {
            is RestSessionRecordResult.Resolved -> session.record
            is RestSessionRecordResult.NotFound -> {
                call.respond(HttpStatusCode.NotFound, RestError(session.message))
                return@get
            }
        }
        val project = when (val projectResult = resolver.resolve(projectKey = record.projectKey)) {
            is WorkspaceProjectResolution.Resolved -> projectResult.project
            is WorkspaceProjectResolution.Unresolved -> {
                call.respond(HttpStatusCode.NotFound, RestError(projectResult.message))
                return@get
            }
        }

        val options = SymbolSearchOptions(
            query = query,
            libraries = resource.libraries,
            kind = kind,
            limit = resource.limit.coerceAtMost(MaxSymbolLimit),
            timeoutMillis = resource.timeoutMillis,
        )
        respondSymbolSearch(call, project, options)
    }
}

private suspend fun respondSymbolSearch(
    call: ApplicationCall,
    project: Project,
    options: SymbolSearchOptions,
) {
    if (DumbService.isDumb(project)) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            RestError("Symbol search is unavailable while indexes are updating. Please retry after indexing completes."),
        )
        return
    }
    val response = try {
        executeSymbolSearch(project, options)
    } catch (_: IndexNotReadyException) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            RestError("Symbol search is unavailable while indexes are updating. Please retry after indexing completes."),
        )
        return
    }
    call.respond(response)
}

private suspend fun executeSymbolSearch(
    project: Project,
    options: SymbolSearchOptions,
): SymbolSearchResponse {
    val modelDisposable = Disposer.newDisposable("RestSymbolSearchRoutes.search")
    val diagnostics = mutableListOf<String>()
    var timedOut: Boolean
    var collection = RawCollectionResult(candidates = emptyList(), observedCount = 0, completed = true)

    try {
        val scope = if (options.libraries) {
            ProjectScope.getAllScope(project)
        } else {
            ProjectScope.getProjectScope(project)
        }
        val parameters = FindSymbolParameters.wrap(options.query, project, options.libraries).withScope(scope)
        val model = GotoSymbolModel2(project, modelDisposable)
        val viewModel = StaticChooseByNameViewModel(
            project = project,
            model = model,
            maximumListSizeLimit = options.limit,
        )
        val provider = ChooseByNameModelEx.getItemProvider(model, null)
        val providerName = provider.javaClass.name
        if (provider !is ChooseByNameInScopeItemProvider) {
            diagnostics += "Item provider $providerName does not support in-scope search; using model fallback."
        }

        timedOut = withTimeoutOrNull(options.timeoutMillis.milliseconds) {
            withBackgroundProgress(
                project,
                "Searching symbols: ${options.query}",
                cancellable = true,
            ) {
                collection = coroutineToIndicator { indicator ->
                    runBlockingCancellable {
                        readAction {
                            if (provider is ChooseByNameInScopeItemProvider) {
                                val primary = collectRawCandidatesByInScopeProvider(
                                    provider = provider,
                                    viewModel = viewModel,
                                    parameters = parameters,
                                    indicator = indicator,
                                    limit = options.limit,
                                )
                                if (primary.candidates.isEmpty()) {
                                    collectRawCandidatesByModelFallback(
                                        model = model,
                                        parameters = parameters,
                                        indicator = indicator,
                                        limit = options.limit,
                                    )
                                } else {
                                    primary
                                }
                            } else {
                                collectRawCandidatesByModelFallback(
                                    model = model,
                                    parameters = parameters,
                                    indicator = indicator,
                                    limit = options.limit,
                                )
                            }
                        }
                    }
                }
            }
            false
        } ?: true

        val converted = convertCandidates(
            project = project,
            model = model,
            candidates = collection.candidates,
            kindFilter = options.kind,
        )
        diagnostics += converted.diagnostics
        if (timedOut) {
            diagnostics += "Symbol search timed out before completion."
        }
        if (collection.candidates.isEmpty()) {
            diagnostics += "Provider $providerName returned no raw symbol candidates."
        }

        val sorted = converted.items.sortedWith(
            compareByDescending<SymbolSearchItem> { it.score ?: Int.MIN_VALUE }
                .thenBy { it.name }
                .thenBy { it.filePath }
                .thenBy { it.line }
                .thenBy { it.column },
        )
        val items = sorted.take(options.limit)
        val truncated = timedOut || !collection.completed || sorted.size > options.limit
        return SymbolSearchResponse(
            query = options.query,
            libraries = options.libraries,
            kind = options.kind,
            limit = options.limit,
            timeoutMillis = options.timeoutMillis,
            count = items.size,
            truncated = truncated,
            timedOut = timedOut,
            items = items,
            diagnostics = diagnostics.distinct(),
        )
    } finally {
        Disposer.dispose(modelDisposable)
    }
}

private fun collectRawCandidatesByInScopeProvider(
    provider: ChooseByNameInScopeItemProvider,
    viewModel: ChooseByNameViewModel,
    parameters: FindSymbolParameters,
    indicator: ProgressIndicator,
    limit: Int,
): RawCollectionResult {
    val candidates = mutableListOf<RawCandidate>()
    val observedCount = AtomicInteger(0)
    val completedByProvider = provider.filterElementsWithWeights(
        viewModel,
        parameters,
        indicator,
        Processor { descriptor ->
            indicator.checkCanceled()
            observedCount.incrementAndGet()
            synchronized(candidates) {
                if (candidates.size >= limit) {
                    return@Processor false
                }
                candidates += RawCandidate(item = descriptor.item, score = descriptor.weight)
                candidates.size < limit
            }
        },
    )
    val snapshot = synchronized(candidates) { candidates.toList() }
    return RawCollectionResult(
        candidates = snapshot,
        observedCount = observedCount.get(),
        completed = completedByProvider && snapshot.size < limit,
    )
}

private fun collectRawCandidatesByModelFallback(
    model: GotoSymbolModel2,
    parameters: FindSymbolParameters,
    indicator: ProgressIndicator,
    limit: Int,
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
            if (candidates.size >= limit) {
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
    kindFilter: String?,
): ConversionResult {
    val seen = linkedSetOf<String>()
    val items = mutableListOf<SymbolSearchItem>()
    var skippedUnsupported = 0
    var skippedLocation = 0
    var skippedKind = 0
    var skippedDuplicates = 0

    for (candidate in candidates) {
        val converted = convertCandidate(project, model, candidate)
        val item = converted.item
        if (item == null) {
            when (converted.skipReason) {
                SkipReason.UNSUPPORTED_ITEM -> skippedUnsupported++
                SkipReason.LOCATION_UNAVAILABLE -> skippedLocation++
                SkipReason.NONE -> {}
            }
            continue
        }
        if (kindFilter != null && item.kind != kindFilter) {
            skippedKind++
            continue
        }
        val dedupeKey = listOf(
            item.name,
            item.qualifiedName.orEmpty(),
            item.fileUrl,
            item.line.toString(),
            item.column.toString(),
            item.kind,
        )
            .joinToString("|")
        if (!seen.add(dedupeKey)) {
            skippedDuplicates++
            continue
        }
        items += item
    }

    val diagnostics = buildList {
        if (skippedUnsupported > 0) add("Skipped $skippedUnsupported candidates that were neither NavigationItem nor PsiElement.")
        if (skippedLocation > 0) add("Skipped $skippedLocation candidates due to missing physical location data.")
        if (skippedKind > 0) add("Filtered out $skippedKind candidates outside the requested kind.")
        if (skippedDuplicates > 0) add("Removed $skippedDuplicates duplicate symbol entries.")
    }
    return ConversionResult(items = items, diagnostics = diagnostics)
}

private suspend fun convertCandidate(
    project: Project,
    model: GotoSymbolModel2,
    candidate: RawCandidate,
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
        val effectiveFile = effectiveElement?.containingFile ?: return@readAction CandidateConversionResult(
            skipReason = SkipReason.LOCATION_UNAVAILABLE,
        )
        val virtualFile = effectiveFile.virtualFile ?: return@readAction CandidateConversionResult(
            skipReason = SkipReason.LOCATION_UNAVAILABLE,
        )
        val document = PsiDocumentManager.getInstance(project).getLastCommittedDocument(effectiveFile)
            ?: return@readAction CandidateConversionResult(skipReason = SkipReason.LOCATION_UNAVAILABLE)
        val textLength = document.textLength
        val rawOffset = effectiveElement.textOffset.coerceAtLeast(0)
        val safeOffset = if (textLength <= 0) 0 else rawOffset.coerceAtMost(textLength - 1)
        val line = if (textLength <= 0) 1 else document.getLineNumber(safeOffset) + 1
        val lineStartOffset = if (textLength <= 0) 0 else document.getLineStartOffset(line - 1)
        val column = (safeOffset - lineStartOffset) + 1

        val presentation = navigationItem?.presentation
        val rawName = navigationItem?.name
            ?: (effectiveElement as? PsiNamedElement)?.name
            ?: presentation?.presentableText
            ?: source.toString()
        val name = rawName.ifBlank { "<anonymous>" }
        val qualifiedName = runCatching { model.getFullName(navigationItem ?: source) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

        val fileUrl = virtualFile.url
        CandidateConversionResult(
            item = SymbolSearchItem(
                name = name,
                qualifiedName = qualifiedName,
                fileUrl = fileUrl,
                encodedFileUrl = encodeRoutePathSegment(fileUrl),
                filePath = relativizePathOrOriginal(project.basePath, virtualFile.path),
                line = line,
                column = column,
                kind = inferKind(effectiveElement),
                language = effectiveElement.language.id,
                score = candidate.score,
            ),
        )
    }
}

private fun inferKind(element: PsiElement?): String {
    return if (element == null) "unknown" else "symbol"
}

private fun parseSymbolKind(raw: String?): String? {
    val normalized = raw?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
    return normalized.takeIf { it in setOf("class", "method", "field", "symbol", "unknown") }
}

private fun markdownCell(value: String): String {
    return value.replace("|", "\\|").replace("\n", " ")
}

private fun yamlScalar(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

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
    val items: List<SymbolSearchItem>,
    val diagnostics: List<String>,
)

private data class CandidateConversionResult(
    val item: SymbolSearchItem? = null,
    val skipReason: SkipReason = SkipReason.NONE,
)

private enum class SkipReason {
    NONE,
    UNSUPPORTED_ITEM,
    LOCATION_UNAVAILABLE,
}

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
