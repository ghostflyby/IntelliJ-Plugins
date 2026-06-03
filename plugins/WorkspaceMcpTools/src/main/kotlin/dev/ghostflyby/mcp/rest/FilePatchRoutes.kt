package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.filecontent.resolveFileByRelativePathOrNull
import dev.ghostflyby.mcp.filecontent.tools.*
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class PatchResponse(
    val applied: List<Map<String, String>>,
    val failed: List<String> = emptyList(),
    val error: String? = null,
)

// ── Format detection ───────────────────────────────────────

private sealed interface PatchFormat {
    data class Codex(val sections: List<CodexFileSection>) : PatchFormat
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
        first.startsWith("*** ") -> PatchFormat.Codex(splitCodexByFile(body))
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

    patch("/projects/{projectKey}/files/{relativePath...}") {
        val projectKey = call.parameters["projectKey"] ?: return@patch
        val relativePath = call.parameters.getAll("relativePath")?.joinToString("/") ?: return@patch
        when (val resolved = resolver.resolve(projectKey = projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                val project = resolved.project
                val targetFile = resolveFileByRelativePathOrNull(project, relativePath)
                val isDir = targetFile?.isDirectory == true
                val body = call.receiveText()
                when (val format = detectFormat(body, call.request.contentType())) {
                    is PatchFormat.Unknown -> call.respond(
                        HttpStatusCode.BadRequest,
                        PatchResponse(applied = emptyList(), error = "Unrecognized patch format"),
                    )

                    is PatchFormat.Codex -> applyCodex(project, relativePath, isDir, format.sections, call)
                    is PatchFormat.Git -> applyGit(project, relativePath, isDir, format.patches, call)
                }
            }

            is WorkspaceProjectResolution.Unresolved ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    PatchResponse(applied = emptyList(), failed = listOf(resolved.message)),
                )
        }
    }
}

// ── Codex branch ──────────────────────────────────────────

private suspend fun applyCodex(
    project: com.intellij.openapi.project.Project,
    relativePath: String, isDir: Boolean,
    sections: List<CodexFileSection>, call: io.ktor.server.application.ApplicationCall,
) {
    if (sections.isEmpty()) {
        call.respond(HttpStatusCode.BadRequest, PatchResponse(applied = emptyList(), error = "No patch sections"))
        return
    }
    val applied = mutableListOf<Map<String, String>>()
    val failed = mutableListOf<String>()
    for (section in sections) {
        try {
            val fileRelPath = if (isDir) "$relativePath/${section.filePath}" else section.filePath
            if (!isDir && section.filePath != relativePath)
                throw IllegalArgumentException("Section targets '${section.filePath}' but target is '$relativePath'")
            val target = resolveProjectPatchPath(project, fileRelPath)
            when (section.operation) {
                CodexFileOperation.ADD -> {
                    createPatchedFile(project, target, parseCodexAddContent(section.rawLines))
                }

                CodexFileOperation.DELETE -> {
                    deletePatchedFile(target, emptyList())
                }

                CodexFileOperation.UPDATE -> {
                    applyFileUpdate(project, target, section.rawLines)
                }
            }
            applied += mapOf("path" to fileRelPath, "operation" to section.operation.name.lowercase())
        } catch (e: Exception) {
            failed += "${section.filePath}: ${e.message ?: "unknown"}"
        }
    }
    call.respond(HttpStatusCode.OK, PatchResponse(applied = applied, failed = failed))
}

// ── Git branch ────────────────────────────────────────────

private suspend fun applyGit(
    project: com.intellij.openapi.project.Project,
    relativePath: String, isDir: Boolean,
    patches: List<TextFilePatch>, call: io.ktor.server.application.ApplicationCall,
) {
    val applied = mutableListOf<Map<String, String>>()
    val failed = mutableListOf<String>()
    for (p in patches) {
        val path = p.afterName ?: p.beforeName ?: continue
        try {
            val fileRelPath = if (isDir) "$relativePath/$path" else path
            if (!isDir && path != relativePath)
                throw IllegalArgumentException("Git patch targets '$path' but target is '$relativePath'")
            val target = resolveProjectPatchPath(project, fileRelPath)
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

private fun gitHunksToRawLines(patch: TextFilePatch): List<String> {
    val lines = mutableListOf<String>()
    for (hunk in patch.hunks) {
        lines += "@@ -${hunk.startLineBefore + 1},${hunk.endLineBefore - hunk.startLineBefore} " +
                "+${hunk.startLineAfter + 1},${hunk.endLineAfter - hunk.startLineAfter} @@"
        for (pl in hunk.lines) {
            lines += when (pl.type) {
                com.intellij.openapi.diff.impl.patch.PatchLine.Type.ADD -> "+${pl.text}"
                com.intellij.openapi.diff.impl.patch.PatchLine.Type.REMOVE -> "-${pl.text}"
                com.intellij.openapi.diff.impl.patch.PatchLine.Type.CONTEXT -> " ${pl.text}"
            }
        }
    }
    return lines
}

// ── Shared EDT-safe update ─────────────────────────────────

private suspend fun applyFileUpdate(
    project: com.intellij.openapi.project.Project,
    target: ProjectPatchPath,
    rawLines: List<String>,
) {
    edtWriteAction {
        val vf = VirtualFileManager.getInstance().findFileByUrl(target.url)
            ?: error("File not found: ${target.relativePath}")
        val doc = FileDocumentManager.getInstance().getDocument(vf)
            ?: error("Binary or no text: ${target.relativePath}")
        if (!doc.isWritable) error("Not writable: ${target.relativePath}")
        val hunks = parseCodexRawHunks(rawLines, doc.immutableCharSequence)
            ?: error("No valid hunks")
        val applied = GenericPatchApplier.apply(doc.immutableCharSequence, hunks)
            ?: error("Patch does not apply for ${target.relativePath}")
        doc.setText(applied.patchedText)
        val mgr = com.intellij.psi.PsiDocumentManager.getInstance(project)
        mgr.doPostponedOperationsAndUnblockDocument(doc)
        mgr.commitDocument(doc)
    }
}
