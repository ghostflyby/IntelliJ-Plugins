/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.ide.util.gotoByName.ChooseByNameInScopeItemProvider
import com.intellij.ide.util.gotoByName.ChooseByNameModel
import com.intellij.ide.util.gotoByName.ChooseByNameViewModel
import com.intellij.ide.util.gotoByName.GotoFileModel
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.progress.runBlockingCancellable
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
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
import java.nio.file.Path
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

private const val DefaultFileSearchLimit: Int = 50
private const val MaxFileSearchLimit: Int = 200
private const val DefaultFileSearchTimeoutMillis: Int = 20_000

@Serializable
private data class FileSearchItem(
    val name: String,
    val fileUrl: String,
    val encodedFileUrl: String,
    val filePath: String,
    val relativePath: String,
    val line: Int,
    val column: Int,
    val fileType: String,
    val score: Int? = null,
)

@Serializable
private data class FileSearchResponse(
    val query: String,
    val pathPrefix: String,
    val limit: Int = DefaultFileSearchLimit,
    val timeoutMillis: Int = DefaultFileSearchTimeoutMillis,
    val count: Int = 0,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val items: List<FileSearchItem> = emptyList(),
    val diagnostics: List<String> = emptyList(),
) : TextBody {
    override fun renderTextBody(): String = buildString {
        appendLine("---")
        appendLine("query: ${yamlScalar(query)}")
        appendLine("pathPrefix: ${yamlScalar(pathPrefix)}")
        appendLine("limit: $limit")
        appendLine("timeoutMillis: $timeoutMillis")
        appendLine("count: $count")
        appendLine("truncated: $truncated")
        appendLine("timedOut: $timedOut")
        appendLine("---")
        if (items.isEmpty()) {
            appendLine("No files")
            appendDiagnostics(diagnostics)
            return@buildString
        }
        appendLine("## Files")
        appendLine("| name | path | encodedFileUrl | fileType | score |")
        appendLine("| --- | --- | --- | --- | ---: |")
        items.forEach { item ->
            val fileReference = markdownFileReference(
                filePath = item.filePath,
                fileUrl = item.fileUrl,
                encodedFileUrl = item.encodedFileUrl,
            )
            appendLine(
                "| ${markdownCell(item.name)} | ${markdownCell(fileReference.path)} | " +
                        "${markdownCell(fileReference.encodedFileUrl)} | " +
                        "${markdownCell(item.fileType)} | ${item.score ?: ""} |",
            )
        }
        appendDiagnostics(diagnostics)
    }
}

private data class FileSearchOptions(
    val query: String,
    val limit: Int,
    val timeoutMillis: Int,
)

private data class FileSearchRoot(
    val directory: VirtualFile,
    val pathPrefix: Path,
)

internal fun Route.searchFileRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    get<Api.SearchFilesEntry> { resource ->
        val query = resource.query.trim()
        if (query.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, RestError("query must not be blank."))
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
        val root = resolveFileSearchRoot(record)
        if (root == null) {
            call.respond(HttpStatusCode.NotFound, RestError("Session path prefix is not available in the local VFS."))
            return@get
        }

        respondFileSearch(
            call = call,
            project = project,
            root = root,
            options = FileSearchOptions(
                query = query,
                limit = resource.limit.coerceAtMost(MaxFileSearchLimit),
                timeoutMillis = resource.timeoutMillis,
            ),
        )
    }
}

private fun resolveFileSearchRoot(record: RestSessionRecord): FileSearchRoot? {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(record.pathPrefix) ?: return null
    val directory = if (file.isDirectory) file else file.parent ?: return null
    val prefix = if (file.isDirectory) record.pathPrefix else record.pathPrefix.parent ?: record.pathPrefix
    return FileSearchRoot(directory = directory, pathPrefix = prefix)
}

private suspend fun respondFileSearch(
    call: ApplicationCall,
    project: Project,
    root: FileSearchRoot,
    options: FileSearchOptions,
) {
    if (DumbService.isDumb(project)) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            RestError("File search is unavailable while indexes are updating. Please retry after indexing completes."),
        )
        return
    }
    val response = try {
        executeFileSearch(project, root, options)
    } catch (_: IndexNotReadyException) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            RestError("File search is unavailable while indexes are updating. Please retry after indexing completes."),
        )
        return
    }
    call.respond(response)
}

private suspend fun executeFileSearch(
    project: Project,
    root: FileSearchRoot,
    options: FileSearchOptions,
): FileSearchResponse {
    val diagnostics = mutableListOf<String>()
    var timedOut: Boolean
    var collection = FileRawCollectionResult(candidates = emptyList(), observedCount = 0, completed = true)
    val model = GotoFileModel(project)
    val viewModel = FileSearchChooseByNameViewModel(
        project = project,
        model = model,
        maximumListSizeLimit = options.limit,
    )
    val provider = model.getItemProvider(null)
    val providerName = provider.javaClass.name
    val scope = GlobalSearchScopesCore.directoryScope(project, root.directory, true)
    val parameters = FindSymbolParameters.wrap(options.query, project, false).withScope(scope)

    timedOut = withTimeoutOrNull(options.timeoutMillis.milliseconds) {
        withBackgroundProgress(
            project,
            "Searching files: ${options.query}",
            cancellable = true,
        ) {
            collection = coroutineToIndicator { indicator ->
                runBlockingCancellable {
                    readAction {
                        if (provider is ChooseByNameInScopeItemProvider) {
                            collectFileCandidatesByInScopeProvider(
                                provider = provider,
                                viewModel = viewModel,
                                parameters = parameters,
                                indicator = indicator,
                                limit = options.limit,
                            )
                        } else {
                            diagnostics += "Item provider $providerName does not support in-scope search; using model fallback."
                            collectFileCandidatesByModelFallback(
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

    val converted = convertFileCandidates(project, root, collection.candidates)
    diagnostics += converted.diagnostics
    if (timedOut) {
        diagnostics += "File search timed out before completion."
    }
    if (collection.candidates.isEmpty()) {
        diagnostics += "Provider $providerName returned no raw file candidates."
    }

    val sorted = converted.items.sortedWith(
        compareByDescending<FileSearchItem> { it.score ?: Int.MIN_VALUE }
            .thenBy { it.relativePath }
            .thenBy { it.name },
    )
    val items = sorted.take(options.limit)
    val truncated = timedOut || !collection.completed || sorted.size > options.limit
    return FileSearchResponse(
        query = options.query,
        pathPrefix = root.pathPrefix.toString(),
        limit = options.limit,
        timeoutMillis = options.timeoutMillis,
        count = items.size,
        truncated = truncated,
        timedOut = timedOut,
        items = items,
        diagnostics = diagnostics.distinct(),
    )
}

private fun collectFileCandidatesByInScopeProvider(
    provider: ChooseByNameInScopeItemProvider,
    viewModel: ChooseByNameViewModel,
    parameters: FindSymbolParameters,
    indicator: ProgressIndicator,
    limit: Int,
): FileRawCollectionResult {
    val candidates = mutableListOf<FileRawCandidate>()
    val observedCount = AtomicInteger(0)
    val completedByProvider = provider.filterElementsWithWeights(
        viewModel,
        parameters,
        indicator,
        Processor {
            indicator.checkCanceled()
            observedCount.incrementAndGet()
            synchronized(candidates) {
                if (candidates.size >= limit) {
                    return@Processor false
                }
                candidates += FileRawCandidate(item = it.item, score = it.weight)
                candidates.size < limit
            }
        },
    )
    val snapshot = synchronized(candidates) { candidates.toList() }
    return FileRawCollectionResult(
        candidates = snapshot,
        observedCount = observedCount.get(),
        completed = completedByProvider && snapshot.size < limit,
    )
}

private fun collectFileCandidatesByModelFallback(
    model: GotoFileModel,
    parameters: FindSymbolParameters,
    indicator: ProgressIndicator,
    limit: Int,
): FileRawCollectionResult {
    val names = Collections.synchronizedSet(linkedSetOf<String>())
    val candidates = mutableListOf<FileRawCandidate>()
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
                return FileRawCollectionResult(
                    candidates = candidates.toList(),
                    observedCount = observedCount,
                    completed = false,
                )
            }
            candidates += FileRawCandidate(item = element, score = null)
        }
    }

    return FileRawCollectionResult(
        candidates = candidates.toList(),
        observedCount = observedCount,
        completed = true,
    )
}

private suspend fun convertFileCandidates(
    project: Project,
    root: FileSearchRoot,
    candidates: List<FileRawCandidate>,
): FileConversionResult {
    val seen = linkedSetOf<String>()
    val items = mutableListOf<FileSearchItem>()
    var skippedUnsupported = 0
    var skippedDirectory = 0
    var skippedDuplicates = 0

    for (candidate in candidates) {
        val converted = convertFileCandidate(project, root, candidate)
        val item = converted.item
        if (item == null) {
            when (converted.skipReason) {
                FileSkipReason.UNSUPPORTED_ITEM -> skippedUnsupported++
                FileSkipReason.DIRECTORY -> skippedDirectory++
                FileSkipReason.NONE -> {}
            }
            continue
        }
        if (!seen.add(item.fileUrl)) {
            skippedDuplicates++
            continue
        }
        items += item
    }

    val diagnostics = buildList {
        if (skippedUnsupported > 0) add("Skipped $skippedUnsupported candidates that were not files.")
        if (skippedDirectory > 0) add("Skipped $skippedDirectory directory candidates.")
        if (skippedDuplicates > 0) add("Removed $skippedDuplicates duplicate file entries.")
    }
    return FileConversionResult(items = items, diagnostics = diagnostics)
}

private suspend fun convertFileCandidate(
    project: Project,
    root: FileSearchRoot,
    candidate: FileRawCandidate,
): FileCandidateConversionResult {
    return readAction {
        val psiFile = when (val source = candidate.item) {
            is PsiFile -> source
            is PsiDirectory -> return@readAction FileCandidateConversionResult(skipReason = FileSkipReason.DIRECTORY)
            else -> return@readAction FileCandidateConversionResult(skipReason = FileSkipReason.UNSUPPORTED_ITEM)
        }
        val virtualFile = psiFile.virtualFile
            ?: return@readAction FileCandidateConversionResult(skipReason = FileSkipReason.UNSUPPORTED_ITEM)
        val path = virtualFile.path
        val relativePath = relativizePathOrOriginal(root.pathPrefix.toString(), path)
        val fileUrl = virtualFile.url
        FileCandidateConversionResult(
            item = FileSearchItem(
                name = virtualFile.name,
                fileUrl = fileUrl,
                encodedFileUrl = encodeRoutePathSegment(fileUrl),
                filePath = relativizePathOrOriginal(project.basePath, path),
                relativePath = relativePath,
                line = 1,
                column = 1,
                fileType = virtualFile.fileType.name,
                score = candidate.score,
            ),
        )
    }
}

private fun StringBuilder.appendDiagnostics(diagnostics: List<String>) {
    if (diagnostics.isNotEmpty()) {
        appendLine("## Diagnostics")
        diagnostics.forEach { appendLine("- $it") }
    }
}

private fun markdownCell(value: String): String {
    return value.replace("|", "\\|").replace("\n", " ")
}

private fun yamlScalar(value: String): String {
    val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
    return "\"$escaped\""
}

private data class FileRawCandidate(
    val item: Any,
    val score: Int?,
)

private data class FileRawCollectionResult(
    val candidates: List<FileRawCandidate>,
    val observedCount: Int,
    val completed: Boolean,
)

private data class FileConversionResult(
    val items: List<FileSearchItem>,
    val diagnostics: List<String>,
)

private data class FileCandidateConversionResult(
    val item: FileSearchItem? = null,
    val skipReason: FileSkipReason = FileSkipReason.NONE,
)

private enum class FileSkipReason {
    NONE,
    UNSUPPORTED_ITEM,
    DIRECTORY,
}

private class FileSearchChooseByNameViewModel(
    private val project: Project,
    private val model: ChooseByNameModel,
    private val maximumListSizeLimit: Int,
) : ChooseByNameViewModel {
    override fun getProject(): Project = project
    override fun getModel(): ChooseByNameModel = model
    override fun isSearchInAnyPlace(): Boolean = false
    override fun transformPattern(pattern: String): String = pattern
    override fun canShowListForEmptyPattern(): Boolean = false
    override fun getMaximumListSizeLimit(): Int = maximumListSizeLimit
}
