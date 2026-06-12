package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.PatchReader
import com.intellij.openapi.diff.impl.patch.TextFilePatch
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.filecontent.*
import dev.ghostflyby.mcp.patch.*
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.resources.patch
import io.ktor.server.response.*
import io.ktor.server.routing.Route
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

    patch<Api.Project.Root.File> { resource ->
        val projectKey = resource.projectKey
        when (val resolved = resolver.resolve(projectKey = projectKey)) {
            is WorkspaceProjectResolution.Resolved -> {
                val project = resolved.project
                val relativePath = resource.relativePath.toRoutePath()
                val target = call.rootRouteTargetOrNotFound(project, resource.parent.rootId, relativePath)
                    ?: return@patch
                val access = resolveProjectFileAccess(project, target.root, target.relativePath)
                val force = resource.force
                if (access.targetIsBinary) {
                    call.respond(
                        HttpStatusCode.UnsupportedMediaType,
                        PatchResponse(applied = emptyList(), error = "Binary patches are not supported"),
                    )
                    return@patch
                }
                if (access.file?.isDirectory != true && !access.policy.canWrite(FileContentKind.PATCH, force)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        PatchResponse(applied = emptyList(), error = access.policy.reason),
                    )
                    return@patch
                }
                val targetFile = access.file
                val isDir = targetFile?.isDirectory == true
                val body = call.receiveText()
                when (val format = detectFormat(body, call.request.contentType())) {
                    is PatchFormat.Unknown -> call.respond(
                        HttpStatusCode.BadRequest,
                        PatchResponse(applied = emptyList(), error = "Unrecognized patch format"),
                    )

                    is PatchFormat.Codex -> applyCodex(project, access, isDir, force, format.sections, call)
                    is PatchFormat.Git -> applyGit(project, access, isDir, force, format.patches, call)
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
    routeAccess: ProjectFileAccess,
    isDir: Boolean,
    force: Boolean,
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
                    applyFileUpdate(project, target, section.rawLines, access.policy, force)
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
    routeAccess: ProjectFileAccess,
    isDir: Boolean, force: Boolean,
    patches: List<TextFilePatch>, call: io.ktor.server.application.ApplicationCall,
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
                else -> applyFileUpdate(project, target, gitHunksToRawLines(p), access.policy, force)
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
    project: com.intellij.openapi.project.Project,
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
    policy: FileAccessPolicy,
    force: Boolean = false,
) {
    edtWriteAction {
        val vf = VirtualFileManager.getInstance().findFileByUrl(target.url)
            ?: error("File not found: ${target.relativePath}")
        val fileDocumentManager = FileDocumentManager.getInstance()
        val doc = if (policy.classification == FileContentClassification.IGNORED_TEXT && force) {
            fileDocumentManager.getCachedDocument(vf)
        } else {
            fileDocumentManager.getDocument(vf)
        }
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
            fileDocumentManager.saveDocument(doc)
        } else {
            vf.setBinaryContent(applied.patchedText.toByteArray(vf.charset))
        }
    }
}
