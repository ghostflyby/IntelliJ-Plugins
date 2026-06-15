package dev.ghostflyby.mcp.rest

import com.intellij.codeInsight.actions.CodeCleanupCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.PatchLine
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import dev.ghostflyby.mcp.filecontent.FileContentKind
import dev.ghostflyby.mcp.filecontent.ProjectFileAccess
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.filecontent.resolveProjectFileAccess
import dev.ghostflyby.mcp.patch.*
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.*
import io.ktor.server.resources.patch
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable
private data class PatchResponse(
    val applied: List<Map<String, String>>,
    val failed: List<String> = emptyList(),
    val error: String? = null,
) : TextBody {
    override fun renderTextBody(): String = buildString {
        error?.let {
            appendLine("ERROR: $it")
            return@buildString
        }
        if (applied.isNotEmpty()) {
            appendLine("applied:")
            applied.forEach { item ->
                val path = item["path"].orEmpty()
                val operation = item["operation"].orEmpty()
                appendLine("- $operation $path".trimEnd())
            }
        } else {
            appendLine("applied: none")
        }
        if (failed.isNotEmpty()) {
            appendLine("failed:")
            failed.forEach { appendLine("- $it") }
        }
    }
}

// ── Format detection ───────────────────────────────────────

private sealed interface PatchFormat {
    data class Codex(val body: String, val sections: List<CodexFileSection>) : PatchFormat
    data class Git(val patches: List<TextFilePatch>) : PatchFormat
    data object Unknown : PatchFormat
}

private fun detectFormat(body: String, ct: ContentType?): PatchFormat {
    // Explicit text/x-patch → git patch only
    if (ct?.match(ContentType("text", "x-patch")) == true) {
        val patches = runCatching { PatchReader(body).readTextPatches() }.getOrDefault(emptyList())
        if (patches.isEmpty()) return PatchFormat.Unknown
        return PatchFormat.Git(patches)
    }
    // Auto-detect by first line
    val first = body.substringBefore('\n').trim()
    return when {
        first.startsWith("*** ") -> PatchFormat.Codex(body, splitCodexByFile(body))
        first.startsWith("diff --git") || first.startsWith("--- ") -> {
            val patches = runCatching { PatchReader(body).readTextPatches() }.getOrDefault(emptyList())
            if (patches.isEmpty()) PatchFormat.Unknown else PatchFormat.Git(patches)
        }

        else -> PatchFormat.Unknown
    }
}

// ── Route ──────────────────────────────────────────────────

internal fun Route.filePatchRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()

    patch<Api.FilesEntry.File> { resource ->
        val target =
            when (val resolved = call.resolveFileRouteTarget(sessions, resolver, resource.path.toRoutePath())) {
                is RestFileRouteTarget.ProjectFile -> resolved.target
                is RestFileRouteTarget.VirtualFileReadOnly -> {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        PatchResponse(
                            applied = emptyList(),
                            error = "VFS URL patches are allowed only for files in the session project workspace",
                        ),
                    )
                    null
                }

                null -> null
            }
                ?: return@patch
        handleSessionPatch(
            call,
            target,
            resource.parent.force,
            resource.parent.problemFix,
        )
    }
}

internal suspend fun handleSessionPatch(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    force: Boolean,
    problemFix: Boolean = false,
) {
    val project = target.project
    val access = resolveProjectFileAccess(project, target.root, target.relativePath)
    if (problemFix) {
        call.respond(
            HttpStatusCode.Conflict,
            PatchResponse(
                applied = emptyList(),
                error = "Problem fixes are not supported without IntelliJ public APIs for problem quick-fix discovery.",
            ),
        )
        return
    }
    if (access.targetIsBinary) {
        call.respond(
            HttpStatusCode.UnsupportedMediaType,
            PatchResponse(applied = emptyList(), error = "Binary patches are not supported"),
        )
        return
    }
    if (access.file?.isDirectory != true && !access.policy.canWrite(FileContentKind.PATCH, force)) {
        call.respond(
            HttpStatusCode.Forbidden,
            PatchResponse(applied = emptyList(), error = access.policy.reason),
        )
        return
    }
    val targetFile = access.file
    val isDir = targetFile?.isDirectory == true
    val body = call.receiveText()
    when (val format = detectFormat(body, call.request.contentType())) {
        is PatchFormat.Unknown -> call.respond(
            HttpStatusCode.BadRequest,
            PatchResponse(applied = emptyList(), error = "Unrecognized patch format"),
        )

        is PatchFormat.Codex -> applyCodex(project, access, isDir, force, format.body, format.sections, call)
        is PatchFormat.Git -> applyGit(project, access, isDir, force, format.patches, call)
    }
}

// ── Codex branch ──────────────────────────────────────────

private suspend fun applyCodex(
    project: Project,
    routeAccess: ProjectFileAccess,
    isDir: Boolean,
    force: Boolean,
    body: String,
    sections: List<CodexFileSection>, call: ApplicationCall,
) {
    if (sections.isEmpty()) {
        val workspaceOperations = parseWorkspaceOperations(body)
        if (workspaceOperations.isNotEmpty()) {
            applyWorkspaceOperations(project, routeAccess, isDir, force, workspaceOperations, call)
            return
        }
        call.respond(HttpStatusCode.BadRequest, PatchResponse(applied = emptyList(), error = "No patch sections"))
        return
    }
    val applied = mutableListOf<Map<String, String>>()
    val failed = mutableListOf<String>()
    for (section in sections) {
        try {
            val fileRelPath =
                if (isDir) joinRelativePath(routeAccess.relativePath, section.filePath) else routeAccess.relativePath
            val access = resolvePatchSectionAccess(project, routeAccess, fileRelPath)
            ensurePatchAllowed(access, force)
            val target = patchPathFor(access)
            when (section.operation) {
                CodexFileOperation.ADD -> {
                    createPatchedFile(project, target, parseCodexAddContent(section.rawLines))
                }

                CodexFileOperation.DELETE -> {
                    deletePatchedFile(target, emptyList())
                }

                CodexFileOperation.UPDATE -> {
                    if (section.rawLines.any { it.startsWith("@@") }) {
                        applyFileUpdate(project, target, section.rawLines)
                    } else if (section.moveTo == null) {
                        error("No valid hunks")
                    }
                    section.moveTo?.let { moveTo ->
                        val moveRelPath = if (isDir) joinRelativePath(routeAccess.relativePath, moveTo) else moveTo
                        val moveAccess = resolvePatchSectionAccess(project, routeAccess, moveRelPath)
                        ensurePatchAllowed(moveAccess, force)
                        movePatchedFile(target, patchPathFor(moveAccess))
                    }
                }
            }
            applied += mapOf("path" to fileRelPath, "operation" to section.operation.name.lowercase())
        } catch (e: Exception) {
            failed += "${section.filePath}: ${e.message ?: "unknown"}"
        }
    }
    call.respond(HttpStatusCode.OK, PatchResponse(applied = applied, failed = failed))
}

private enum class WorkspaceOperationKind(val wireName: String, val resultName: String) {
    OPTIMIZE_IMPORTS("Optimize Imports", "optimize-imports"),
    REFORMAT_FILE("Reformat File", "reformat"),
    CLEANUP("Cleanup", "cleanup"),
    FIX_PROBLEM("Fix Problem", "fix-problem"),
}

private data class WorkspaceOperation(
    val kind: WorkspaceOperationKind,
    val filePath: String?,
)

private data class WorkspaceOperationTarget(
    val fileRelPath: String,
    val kinds: MutableSet<WorkspaceOperationKind> = linkedSetOf(),
)

private val WORKSPACE_OPERATION_ORDER = listOf(
    WorkspaceOperationKind.FIX_PROBLEM,
    WorkspaceOperationKind.CLEANUP,
    WorkspaceOperationKind.OPTIMIZE_IMPORTS,
    WorkspaceOperationKind.REFORMAT_FILE,
)

private val WORKSPACE_OPERATION = Regex("""^\*\*\* (Optimize Imports|Reformat File|Cleanup|Fix Problem)(?:: (.+))?$""")

private fun parseWorkspaceOperations(body: String): List<WorkspaceOperation> {
    return body.lineSequence().mapNotNull { rawLine ->
        val match = WORKSPACE_OPERATION.find(rawLine) ?: return@mapNotNull null
        val kind = WorkspaceOperationKind.entries.first { it.wireName == match.groupValues[1] }
        WorkspaceOperation(kind, match.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() })
    }.toList()
}

private suspend fun applyWorkspaceOperations(
    project: Project,
    routeAccess: ProjectFileAccess,
    isDir: Boolean,
    force: Boolean,
    operations: List<WorkspaceOperation>,
    call: ApplicationCall,
) {
    val applied = mutableListOf<Map<String, String>>()
    val failed = mutableListOf<String>()
    val targets = linkedMapOf<String, WorkspaceOperationTarget>()
    for (operation in operations) {
        val pathForError = operation.filePath ?: routeAccess.relativePath.ifBlank { "." }
        try {
            val fileRelPath = operation.filePath?.let { opPath ->
                if (isDir) joinRelativePath(routeAccess.relativePath, opPath) else opPath
            } ?: routeAccess.relativePath.ifBlank {
                error("${operation.kind.wireName} requires a file path when PATCH target is the session root")
            }
            targets.getOrPut(fileRelPath) { WorkspaceOperationTarget(fileRelPath) }.kinds += operation.kind
        } catch (e: Exception) {
            failed += "$pathForError: ${e.message ?: "unknown"}"
        }
    }
    for (target in targets.values) {
        val access = try {
            val access = resolvePatchSectionAccess(project, routeAccess, target.fileRelPath)
            ensurePatchAllowed(access, force)
            access
        } catch (e: Exception) {
            failed += "${target.fileRelPath}: ${e.message ?: "unknown"}"
            continue
        }
        val file = access.file ?: run {
            failed += "${target.fileRelPath}: File not found: ${target.fileRelPath}"
            continue
        }
        val psiFile = try {
            resolveWritablePsiFile(project, file)
        } catch (e: Exception) {
            failed += "${target.fileRelPath}: ${e.message ?: "unknown"}"
            continue
        }
        val orderedKinds = WORKSPACE_OPERATION_ORDER.filter { it in target.kinds }
        for (kind in orderedKinds) {
            try {
                when (kind) {
                    WorkspaceOperationKind.OPTIMIZE_IMPORTS -> runCodeProcessor(project, file, psiFile) {
                        OptimizeImportsProcessor(project, psiFile).run()
                    }

                    WorkspaceOperationKind.REFORMAT_FILE -> runCodeProcessor(project, file, psiFile) {
                        ReformatCodeProcessor(psiFile, false).run()
                    }

                    WorkspaceOperationKind.CLEANUP -> runCodeProcessor(project, file, psiFile) {
                        CodeCleanupCodeProcessor(project, arrayOf(psiFile), null, false).run()
                    }

                    WorkspaceOperationKind.FIX_PROBLEM -> error(
                        "Problem fixes are not supported without IntelliJ public APIs for problem quick-fix discovery.",
                    )
                }
                applied += mapOf("path" to target.fileRelPath, "operation" to kind.resultName)
            } catch (e: Exception) {
                failed += "${target.fileRelPath}: ${kind.wireName}: ${e.message ?: "unknown"}"
            }
        }
    }
    call.respond(HttpStatusCode.OK, PatchResponse(applied = applied, failed = failed))
}

private suspend fun resolveWritablePsiFile(project: Project, file: com.intellij.openapi.vfs.VirtualFile): PsiFile {
    if (file.isDirectory) error("Workspace operation target must be a file: ${file.path}")
    return readAction { PsiManager.getInstance(project).findFile(file) }
        ?: error("PSI file not found: ${file.path}")
}

private suspend fun runCodeProcessor(
    project: Project,
    file: com.intellij.openapi.vfs.VirtualFile,
    psiFile: PsiFile,
    block: () -> Unit,
) {
    withContext(Dispatchers.EDT) {
        block()
        val document = getOrCreateDocument(file)
        if (document != null) {
            val manager = com.intellij.psi.PsiDocumentManager.getInstance(project)
            manager.doPostponedOperationsAndUnblockDocument(document)
            manager.commitDocument(document)
            FileDocumentManager.getInstance().saveDocument(document)
        }
    }
}

// ── Git branch ────────────────────────────────────────────

private suspend fun applyGit(
    project: Project,
    routeAccess: ProjectFileAccess,
    isDir: Boolean, force: Boolean,
    patches: List<TextFilePatch>, call: ApplicationCall,
) {
    val applied = mutableListOf<Map<String, String>>()
    val failed = mutableListOf<String>()
    for (p in patches) {
        val path = p.afterName ?: p.beforeName ?: continue
        try {
            val fileRelPath = if (isDir) joinRelativePath(routeAccess.relativePath, path) else routeAccess.relativePath
            val access = resolvePatchSectionAccess(project, routeAccess, fileRelPath)
            ensurePatchAllowed(access, force)
            val target = patchPathFor(access)
            when {
                p.isNewFile -> createPatchedFile(project, target, p.singleHunkPatchText)
                p.isDeletedFile -> deletePatchedFile(target, p.hunks)
                else -> applyFileUpdate(project, target, gitHunksToRawLines(p))
            }
            val op = when {
                p.isNewFile -> "add"; p.isDeletedFile -> "delete"; else -> "update"
            }
            applied += mapOf("path" to fileRelPath, "operation" to op)
        } catch (e: Exception) {
            failed += "$path: ${e.message ?: "unknown"}"
        }
    }
    call.respond(HttpStatusCode.OK, PatchResponse(applied = applied, failed = failed))
}

private suspend fun resolvePatchSectionAccess(
    project: Project,
    routeAccess: ProjectFileAccess,
    fileRelPath: String,
): ProjectFileAccess {
    return resolveProjectFileAccess(project, routeAccess.root, fileRelPath)
}

private fun joinRelativePath(base: String, child: String): String =
    if (base.isBlank()) child else "$base/$child"

private fun ensurePatchAllowed(access: ProjectFileAccess, force: Boolean) {
    if (!access.policy.canWrite(FileContentKind.PATCH, force)) {
        throw IllegalArgumentException(access.policy.reason)
    }
    if (access.targetIsBinary) {
        throw IllegalArgumentException("Binary patches are not supported")
    }
}

private fun patchPathFor(access: ProjectFileAccess): ProjectPatchPath {
    val file = access.file
    if (file != null) {
        return ProjectPatchPath(
            relativePath = access.relativePath,
            nioPath = java.nio.file.Path.of(file.path),
            url = file.url,
        )
    }
    val parent = access.parent ?: error("Parent not found: ${access.relativePath}")
    val targetName = access.targetName ?: error("Target name not found: ${access.relativePath}")
    return ProjectPatchPath(
        relativePath = access.relativePath,
        nioPath = java.nio.file.Path.of(parent.path).resolve(targetName),
        url = "${parent.url}/$targetName",
    )
}

private fun gitHunksToRawLines(patch: TextFilePatch): List<String> {
    val lines = mutableListOf<String>()
    for (hunk in patch.hunks) {
        lines += "@@ -${hunk.startLineBefore + 1},${hunk.endLineBefore - hunk.startLineBefore} " +
                "+${hunk.startLineAfter + 1},${hunk.endLineAfter - hunk.startLineAfter} @@"
        for (pl in hunk.lines) {
            lines += when (pl.type) {
                PatchLine.Type.ADD -> "+${pl.text}"
                PatchLine.Type.REMOVE -> "-${pl.text}"
                PatchLine.Type.CONTEXT -> " ${pl.text}"
            }
        }
    }
    return lines
}

// ── Shared EDT-safe update ─────────────────────────────────

private suspend fun applyFileUpdate(
    project: Project,
    target: ProjectPatchPath,
    rawLines: List<String>,
) {
    edtWriteAction {
        val vf = VirtualFileManager.getInstance().findFileByUrl(target.url)
            ?: error("File not found: ${target.relativePath}")
        val doc = getOrCreateDocument(vf)
        val baseText = doc?.immutableCharSequence ?: String(vf.contentsToByteArray(), vf.charset)
        val hunks = parseCodexRawHunks(rawLines, baseText)
            ?: error("No valid hunks")
        val applied = GenericPatchApplier.apply(baseText, hunks)
            ?: error("Patch does not apply for ${target.relativePath}")
        if (doc != null) {
            if (!doc.isWritable) error("Not writable: ${target.relativePath}")
            doc.setText(applied.patchedText)
            val mgr = com.intellij.psi.PsiDocumentManager.getInstance(project)
            mgr.doPostponedOperationsAndUnblockDocument(doc)
            mgr.commitDocument(doc)
            FileDocumentManager.getInstance().saveDocument(doc)
        } else {
            vf.setBinaryContent(applied.patchedText.toByteArray(vf.charset))
        }
    }
}
