/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.problems.WolfTheProblemSolver
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import dev.ghostflyby.mcp.filecontent.FileContentClassification
import dev.ghostflyby.mcp.filecontent.ProjectFileAccess
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.filecontent.resolveProjectFileAccess
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.post
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

@Serializable
internal data class RestProblemReport(
    val path: String,
    val minSeverity: String,
    val count: Int,
    val truncated: Boolean = false,
    val timedOut: Boolean = false,
    val groupCounts: Map<String, Int> = emptyMap(),
    val problems: List<RestProblemItem> = emptyList(),
    val diagnostics: List<String> = emptyList(),
) : TextBody {
    override fun renderTextBody(): String = buildString {
        appendLine("---")
        appendLine("path: ${yamlString(path)}")
        appendLine("minSeverity: ${yamlString(minSeverity)}")
        appendLine("count: $count")
        appendLine("truncated: $truncated")
        appendLine("timedOut: $timedOut")
        if (groupCounts.isNotEmpty()) {
            appendLine("groupCounts:")
            groupCounts.forEach { (key, value) -> appendLine("  ${yamlString(key)}: $value") }
        }
        appendLine("---")
        appendLine("## Problems")
        if (problems.isEmpty()) {
            appendLine("No matching problems.")
        } else {
            appendLine("| severity | file | line | inspection | message | fixes |")
            appendLine("| --- | --- | ---: | --- | --- | --- |")
            problems.forEach { problem ->
                appendLine(
                    "| ${tableCell(problem.severity)} | ${tableCell(problem.filePath ?: problem.fileUrl)} | " +
                            "${problem.line ?: 0} | ${tableCell(problem.inspectionShortName)} | " +
                            "${tableCell(problem.message)} | ${tableCell(problem.fixes.joinToString(", "))} |",
                )
            }
        }
        if (diagnostics.isNotEmpty()) {
            appendLine("## Diagnostics")
            diagnostics.forEach { appendLine("- $it") }
        }
    }
}

@Serializable
internal data class RestProblemItem(
    val severity: String,
    val fileUrl: String,
    val encodedFileUrl: String,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val endLine: Int? = null,
    val endColumn: Int? = null,
    val inspectionShortName: String,
    val inspectionDisplayName: String,
    val groupPath: List<String> = emptyList(),
    val message: String,
    val lineText: String? = null,
    val fixes: List<String> = emptyList(),
)

private data class ProblemQueryOptions(
    val minSeverity: String,
    val name: List<String>,
    val inspection: List<String>,
    val fixable: Boolean,
    val groupBy: List<String>,
    val limit: Int,
)

private data class ProblemTarget(
    val project: Project,
    val file: VirtualFile,
    val displayPath: String,
)

private data class SyntaxProblemSnapshot(
    val startOffset: Int,
    val endOffset: Int,
    val message: String,
    val line: Int,
    val column: Int,
    val endLine: Int,
    val endColumn: Int,
    val lineText: String,
)

internal fun Route.inspectionRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    post<Api.InspectionsEntry.Path> { resource ->
        val target = call.resolveFileRouteTarget(sessions, resolver, resource.path.toRoutePath())
            ?: return@post
        val body = call.receiveText()
        respondInspectionRequest(
            call = call,
            target = target,
            options = resource.parent.toProblemOptions(),
            body = body,
        )
    }
}

internal suspend fun respondFileProblems(
    call: ApplicationCall,
    target: RestFileRouteTarget,
    query: FileQuery,
) {
    respondProblemReport(call, collectProblemTargets(target, query.toProblemOptions(), emptyList()))
}

private suspend fun respondInspectionRequest(
    call: ApplicationCall,
    target: RestFileRouteTarget,
    options: ProblemQueryOptions,
    body: String,
) {
    val inspectPaths = parseInspectFileOperations(body)
    val targets = collectProblemTargets(target, options, inspectPaths)
    respondProblemReport(call, targets)
}

private suspend fun respondProblemReport(
    call: ApplicationCall,
    collection: ProblemTargetCollection,
) {
    if (collection.errorStatus != null) {
        call.respond(collection.errorStatus, RestError(collection.errorMessage.orEmpty()))
        return
    }
    val options = collection.options
    val diagnostics = collection.diagnostics.toMutableList()
    diagnostics += "Problem details are public-API limited: syntax errors come from PSI; file problem state comes from WolfTheProblemSolver. Full inspection quick fixes are not exposed without internal/ex APIs."
    val problems = mutableListOf<RestProblemItem>()
    var truncated = false
    for (target in collection.targets) {
        val remaining = options.limit - problems.size
        if (remaining <= 0) {
            truncated = true
            break
        }
        val fileProblems = collectProblemsForFile(target.project, target.file)
            .filter { it.matches(options) }
        problems += fileProblems.take(remaining)
        if (fileProblems.size > remaining) truncated = true
    }
    val groupCounts = groupCounts(problems, options.groupBy)
    call.respond(
        RestProblemReport(
            path = collection.displayPath,
            minSeverity = options.minSeverity,
            count = problems.size,
            truncated = truncated || collection.truncated,
            groupCounts = groupCounts,
            problems = problems,
            diagnostics = diagnostics,
        ),
    )
}

private data class ProblemTargetCollection(
    val displayPath: String,
    val options: ProblemQueryOptions,
    val targets: List<ProblemTarget> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val truncated: Boolean = false,
    val errorStatus: HttpStatusCode? = null,
    val errorMessage: String? = null,
)

private suspend fun collectProblemTargets(
    target: RestFileRouteTarget,
    options: ProblemQueryOptions,
    inspectPaths: List<String>,
): ProblemTargetCollection {
    return when (target) {
        is RestFileRouteTarget.ProjectFile -> collectProjectProblemTargets(target.target, options, inspectPaths)
        is RestFileRouteTarget.VirtualFileReadOnly -> {
            if (inspectPaths.isNotEmpty()) {
                ProblemTargetCollection(
                    displayPath = target.file.url,
                    options = options,
                    errorStatus = HttpStatusCode.BadRequest,
                    errorMessage = "Inspect File operations are only supported relative to session workspace paths",
                )
            } else {
                collectVirtualFileProblemTargets(target.project, target.file, target.file.url, options)
            }
        }
    }
}

private suspend fun collectProjectProblemTargets(
    target: RestSessionRouteTarget,
    options: ProblemQueryOptions,
    inspectPaths: List<String>,
): ProblemTargetCollection {
    val routeAccess = resolveProjectFileAccess(target.project, target.root, target.relativePath)
    val baseFile = routeAccess.file ?: return ProblemTargetCollection(
        displayPath = target.relativePath,
        options = options,
        errorStatus = HttpStatusCode.NotFound,
        errorMessage = "File not found",
    )
    val diagnostics = mutableListOf<String>()
    val targets = mutableListOf<ProblemTarget>()
    var truncated = false
    if (inspectPaths.isEmpty()) {
        val collected = collectFiles(baseFile, options.limit)
        targets += collected.files.map { ProblemTarget(target.project, it, displayPath(target.project, it) ?: it.url) }
        truncated = collected.truncated
    } else {
        val baseIsDir = baseFile.isDirectory
        inspectPaths.forEach { inspectPath ->
            val relPath = if (baseIsDir) joinRelativePath(routeAccess.relativePath, inspectPath) else inspectPath
            val access = resolveProjectFileAccess(target.project, target.root, relPath)
            val file = access.file
            if (file == null) {
                diagnostics += "Inspect target not found: $inspectPath"
            } else if (!access.isReadableProblemTarget()) {
                diagnostics += "Inspect target is not readable: $inspectPath"
            } else {
                val collected = collectFiles(file, options.limit - targets.size)
                targets += collected.files.map {
                    ProblemTarget(
                        target.project,
                        it,
                        displayPath(target.project, it) ?: it.url,
                    )
                }
                truncated = truncated || collected.truncated
            }
        }
    }
    return ProblemTargetCollection(
        displayPath = target.relativePath.ifBlank { "." },
        options = options,
        targets = targets,
        diagnostics = diagnostics,
        truncated = truncated,
    )
}

private fun ProjectFileAccess.isReadableProblemTarget(): Boolean {
    return policy.classification !in setOf(
        FileContentClassification.EXCLUDED,
        FileContentClassification.OUTSIDE_PROJECT,
    )
}

private fun collectVirtualFileProblemTargets(
    project: Project,
    file: VirtualFile,
    displayPath: String,
    options: ProblemQueryOptions,
): ProblemTargetCollection {
    val collected = collectFiles(file, options.limit)
    return ProblemTargetCollection(
        displayPath = displayPath,
        options = options,
        targets = collected.files.map { ProblemTarget(project, it, displayPath(project, it) ?: it.url) },
        truncated = collected.truncated,
    )
}

private data class FileCollection(val files: List<VirtualFile>, val truncated: Boolean)

private fun collectFiles(root: VirtualFile, limit: Int): FileCollection {
    val files = mutableListOf<VirtualFile>()
    var truncated = false
    fun visit(file: VirtualFile) {
        if (files.size >= limit) {
            truncated = true
            return
        }
        if (!file.isDirectory) {
            if (!file.fileType.isBinary) files += file
            return
        }
        file.children.sortedBy { it.name }.forEach { child ->
            if (!truncated) visit(child)
        }
    }
    visit(root)
    return FileCollection(files, truncated)
}

private suspend fun collectProblemsForFile(project: Project, file: VirtualFile): List<RestProblemItem> {
    val result = mutableListOf<RestProblemItem>()
    result += collectPsiSyntaxErrors(project, file)
    val wolf = WolfTheProblemSolver.getInstance(project)
    val hasSyntaxErrors = readAction { wolf.hasSyntaxErrors(file) }
    val isProblemFile = readAction { wolf.isProblemFile(file) }
    if (hasSyntaxErrors && result.isEmpty()) {
        result += fileStateProblem(project, file, "ERROR", "SyntaxError", "File has syntax errors")
    } else if (isProblemFile && result.isEmpty()) {
        result += fileStateProblem(project, file, "WARNING", "IDEProblemState", "IDE marks this file as problematic")
    }
    return result
}

private suspend fun collectPsiSyntaxErrors(project: Project, file: VirtualFile): List<RestProblemItem> {
    val snapshots = readAction {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return@readAction emptyList()
        val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            ?: getOrCreateDocument(file)
            ?: return@readAction emptyList()
        PsiTreeUtil.findChildrenOfType(psiFile, PsiErrorElement::class.java).map { error ->
            val range = error.textRange
            val startOffset = range.startOffset.coerceIn(0, document.textLength)
            val endOffset = range.endOffset.coerceIn(startOffset, document.textLength)
            val line = document.getLineNumber(startOffset)
            val column = startOffset - document.getLineStartOffset(line)
            val endLine = document.getLineNumber(endOffset)
            val endColumn = endOffset - document.getLineStartOffset(endLine)
            val lineText = document.getText(
                com.intellij.openapi.util.TextRange(
                    document.getLineStartOffset(line),
                    document.getLineEndOffset(line),
                ),
            )
            SyntaxProblemSnapshot(
                startOffset = startOffset,
                endOffset = endOffset,
                message = error.errorDescription,
                line = line + 1,
                column = column + 1,
                endLine = endLine + 1,
                endColumn = endColumn + 1,
                lineText = lineText,
            )
        }
    }
    return snapshots.map { snapshot ->
        RestProblemItem(
            severity = "ERROR",
            fileUrl = file.url,
            encodedFileUrl = encodeRoutePathSegment(file.url),
            filePath = displayPath(project, file),
            line = snapshot.line,
            column = snapshot.column,
            endLine = snapshot.endLine,
            endColumn = snapshot.endColumn,
            inspectionShortName = "SyntaxError",
            inspectionDisplayName = "Syntax error",
            groupPath = listOf("Syntax"),
            message = snapshot.message,
            lineText = snapshot.lineText,
            fixes = emptyList(),
        )
    }
}

private fun fileStateProblem(
    project: Project,
    file: VirtualFile,
    severity: String,
    inspection: String,
    message: String,
): RestProblemItem = RestProblemItem(
    severity = severity,
    fileUrl = file.url,
    encodedFileUrl = encodeRoutePathSegment(file.url),
    filePath = displayPath(project, file),
    inspectionShortName = inspection,
    inspectionDisplayName = inspection,
    groupPath = listOf("IDE problem state"),
    message = message,
)

private fun RestProblemItem.matches(options: ProblemQueryOptions): Boolean {
    if (severityRank(severity) < severityRank(options.minSeverity)) return false
    if (options.fixable && fixes.isEmpty()) return false
    if (options.inspection.isNotEmpty() && inspectionShortName !in options.inspection) return false
    if (options.name.isNotEmpty()) {
        val haystack = (listOf(inspectionShortName, inspectionDisplayName, message) + groupPath)
            .joinToString("\n")
            .lowercase()
        if (options.name.none { haystack.contains(it.lowercase()) }) return false
    }
    return true
}

private fun groupCounts(problems: List<RestProblemItem>, groupBy: List<String>): Map<String, Int> {
    if (groupBy.isEmpty()) return emptyMap()
    return problems.groupingBy { problem ->
        groupBy.joinToString("/") { group ->
            when (group.lowercase()) {
                "severity" -> problem.severity
                "inspection" -> problem.inspectionShortName
                "file" -> problem.filePath ?: problem.fileUrl
                else -> group
            }
        }
    }.eachCount().toSortedMap()
}

private fun parseInspectFileOperations(body: String): List<String> {
    if (body.isBlank()) return emptyList()
    val regex = Regex("""^\*\*\* Inspect File: (.+)$""")
    return body.lineSequence().mapNotNull { line -> regex.find(line.trim())?.groupValues?.get(1) }.toList()
}

private fun FileQuery.toProblemOptions(): ProblemQueryOptions = ProblemQueryOptions(
    minSeverity = minSeverity,
    name = name,
    inspection = inspection,
    fixable = fixable,
    groupBy = groupBy,
    limit = limit.coerceIn(1, 1_000),
)

private fun Api.InspectionsEntry.toProblemOptions(): ProblemQueryOptions = ProblemQueryOptions(
    minSeverity = minSeverity,
    name = name,
    inspection = inspection,
    fixable = fixable,
    groupBy = groupBy,
    limit = limit.coerceIn(1, 1_000),
)

private fun displayPath(project: Project, file: VirtualFile): String? {
    val basePath = project.basePath ?: return null
    val filePath = file.path
    return if (filePath == basePath) "." else filePath.removePrefix("$basePath/").takeIf { it != filePath }
}

private fun joinRelativePath(base: String, child: String): String =
    if (base.isBlank()) child else "$base/$child"

private fun severityRank(raw: String): Int = when (raw.uppercase()) {
    "ERROR" -> 400
    "WARNING" -> 300
    "WEAK_WARNING" -> 200
    "INFO", "INFORMATION" -> 100
    else -> 0
}

private fun yamlString(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

private fun tableCell(value: String): String = value.replace("|", "\\|").replace("\n", " ")