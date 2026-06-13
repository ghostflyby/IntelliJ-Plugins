/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.find.FindModel
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.util.TextRange
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.psi.search.GlobalSearchScopesCore
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import dev.ghostflyby.mcp.common.relativizePathOrOriginal
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.filecontent.resolveProjectFileAccess
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Serializable
private data class SearchTextHit(
    val filePath: String,
    val lineNumber: Int,
    val column: Int,
    val lineText: String,
    val matchedText: String,
    val startOffset: Int,
    val endOffset: Int,
    val occurrenceId: String,
)

@Serializable
private data class SearchTextResponse(
    val query: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = true,
    val wholeWord: Boolean = false,
    val context: List<String> = listOf("string", "comment", "other"),
    val fileFilter: String? = null,
    val limit: Int = 100,
    val truncated: Boolean = false,
    val hits: List<SearchTextHit> = emptyList(),
) : TextBody {
    override fun renderTextBody(): String = buildString {
        appendLine("---")
        appendLine("query: $query")
        appendLine("regex: $regex")
        appendLine("caseSensitive: $caseSensitive")
        appendLine("wholeWord: $wholeWord")
        fileFilter?.let { appendLine("fileFilter: $it") }
        appendLine("limit: $limit")
        appendLine("truncated: $truncated")
        appendLine("hitCount: ${hits.size}")
        appendLine("---")
        if (hits.isEmpty()) {
            appendLine("No matches")
            return@buildString
        }
        appendLine("## Hits")
        hits.forEach { hit ->
            appendLine("${hit.filePath}:${hit.lineNumber}:${hit.column}")
            appendLine("  ${hit.lineText}")
            appendLine("  match: ${hit.matchedText}")
            appendLine("  occurrenceId: ${hit.occurrenceId}")
        }
    }
}

internal data class SearchTextOptions(
    val query: String,
    val regex: Boolean = false,
    val caseSensitive: Boolean = true,
    val wholeWord: Boolean = false,
    val context: List<String> = listOf("string", "comment", "other"),
    val fileFilter: String? = null,
    val limit: Int = 100,
)

// -- Route registration --

internal fun Route.searchTextRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    get<Api.SearchTextEntry.SearchText> { resource ->
        val entry = resource.parent
        val query = entry.query
        if (query.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, RestError("query must not be blank."))
            return@get
        }
        val target =
            when (val resolved = call.resolveFileRouteTarget(sessions, resolver, resource.path.toRoutePath())) {
                is RestFileRouteTarget.ProjectFile -> resolved.target
                is RestFileRouteTarget.VirtualFileReadOnly -> {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        RestError("Text search requires a directory in the session project workspace"),
                    )
                    null
                }

                null -> null
            }
            ?: return@get
        respondSearchText(
            call,
            target,
            SearchTextOptions(
                query = entry.query,
                regex = entry.regex,
                caseSensitive = entry.caseSensitive,
                wholeWord = entry.wholeWord,
                context = entry.context,
                fileFilter = entry.fileFilter,
                limit = entry.limit,
            ),
        )
    }
}

internal suspend fun respondSearchText(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    entry: SearchTextOptions,
) {
    val rootDir = resolveProjectFileAccess(target.project, target.root, target.relativePath).file
    if (rootDir?.isDirectory != true) {
        call.respond(HttpStatusCode.NotFound, RestError("Search root is not a directory"))
        return
    }
    val contextEnum = mapContext(entry.context)
    if (contextEnum == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            RestError("context '${entry.context.joinToString(",")}' is not supported."),
        )
        return
    }
    val scope = GlobalSearchScopesCore.directoryScope(target.project, rootDir, true)
    val findModel = buildSearchFindModel(entry, contextEnum)
    findModel.customScope = scope
    val hits = executeSearch(target.project, findModel, entry.limit)
    call.respond(
        SearchTextResponse(
            query = entry.query,
            regex = entry.regex,
            caseSensitive = entry.caseSensitive,
            wholeWord = entry.wholeWord,
            context = entry.context,
            fileFilter = entry.fileFilter,
            limit = entry.limit,
            truncated = hits.size >= entry.limit,
            hits = hits,
        ),
    )
}

// -- Context mapping --

private fun mapContext(context: List<String>): FindModel.SearchContext? {
    val set = context.toSet()
    return when {
        set == setOf("string", "comment", "other") || set.isEmpty() -> FindModel.SearchContext.ANY
        set == setOf("string") -> FindModel.SearchContext.IN_STRING_LITERALS
        set == setOf("comment") -> FindModel.SearchContext.IN_COMMENTS
        set == setOf("other") -> FindModel.SearchContext.EXCEPT_COMMENTS_AND_STRING_LITERALS
        set == setOf("string", "other") -> FindModel.SearchContext.EXCEPT_COMMENTS
        set == setOf("comment", "other") -> FindModel.SearchContext.EXCEPT_STRING_LITERALS
        else -> null
    }
}

// -- FindModel --

private fun buildSearchFindModel(
    entry: SearchTextOptions,
    context: FindModel.SearchContext,
): FindModel {
    return FindModel().apply {
        stringToFind = entry.query
        isRegularExpressions = entry.regex
        isCaseSensitive = entry.caseSensitive
        isWholeWordsOnly = entry.wholeWord
        searchContext = context
        fileFilter = entry.fileFilter
        isMultipleFiles = true
        isProjectScope = false
        isWithSubdirectories = true
    }
}

// -- Search execution --

private suspend fun executeSearch(
    project: com.intellij.openapi.project.Project,
    findModel: FindModel,
    limit: Int,
): List<SearchTextHit> {
    val usages = CopyOnWriteArrayList<UsageInfo>()
    val matchedCount = AtomicInteger(0)

    coroutineScope {

        try {
            withBackgroundProgress(
                project,
                "Searching ${findModel.stringToFind}",
                cancellable = true,
            ) {
                coroutineToIndicator { indicator ->
                    FindInProjectUtil.findUsages(
                        findModel,
                        project,
                        indicator,
                        FindUsagesProcessPresentation(UsageViewPresentation()),
                        emptySet(),
                        Processor { usage ->
                            usages += usage
                            matchedCount.incrementAndGet()
                            usages.size < limit
                        },
                    )
                }
            }
        } finally {
        }
    }

    return usages.mapNotNull { usage -> toSearchTextHit(project.basePath, usage) }
}

private const val MAX_LINE_LENGTH = 240

private suspend fun toSearchTextHit(
    projectBasePath: String?,
    usage: UsageInfo,
): SearchTextHit? {

    val file = usage.virtualFile ?: return null
    val navigationRange = usage.navigationRange ?: return null
    val snapshot = readAction {
        val document = getOrCreateDocument(file) ?: return@readAction null

        if (navigationRange.startOffset < 0 || navigationRange.endOffset < navigationRange.startOffset) return@readAction null
        if (navigationRange.endOffset > document.textLength) return@readAction null

        val lineIndex = document.getLineNumber(navigationRange.startOffset)
        val lineStart = document.getLineStartOffset(lineIndex)
        val lineEnd = document.getLineEndOffset(lineIndex)
        val column = navigationRange.startOffset - lineStart + 1
        val lineText = document.getText(TextRange(lineStart, lineEnd))
        val matchedText = document.getText(TextRange(navigationRange.startOffset, navigationRange.endOffset))

        HitSnapshot(lineIndex, lineText, matchedText, column)
    } ?: return null
    var lineText = snapshot.lineText
    if (lineText.length > MAX_LINE_LENGTH) {
        val matchStart = navigationRange.startOffset - (lineText.length - snapshot.lineText.length + lineText.indexOf(snapshot.matchedText))
        // Simple: trim from both ends
        val maxLen = MAX_LINE_LENGTH
        lineText = lineText.substring(0, maxLen - 3) + "..."
    }

    val filePath = relativizePathOrOriginal(projectBasePath, file.path)
    val occurrenceId = sha256ShortHash(
        listOf(file.url, navigationRange.startOffset.toString(), navigationRange.endOffset.toString()).joinToString("|"),
    )

    return SearchTextHit(
        filePath = filePath,
        lineNumber = snapshot.lineIndex + 1,
        column = snapshot.column,
        lineText = lineText,
        matchedText = snapshot.matchedText,
        startOffset = navigationRange.startOffset,
        endOffset = navigationRange.endOffset,
        occurrenceId = occurrenceId,
    )
}

private data class HitSnapshot(
    val lineIndex: Int,
    val lineText: String,
    val matchedText: String,
    val column: Int,
)

private fun sha256ShortHash(text: String, length: Int = 16): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(text.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }.take(length)
}
