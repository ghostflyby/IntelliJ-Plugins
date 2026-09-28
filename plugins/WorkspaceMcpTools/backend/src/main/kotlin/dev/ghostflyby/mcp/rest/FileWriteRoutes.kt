/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.concurrency.annotations.RequiresReadLock
import com.intellij.util.concurrency.annotations.RequiresWriteLock
import dev.ghostflyby.mcp.filecontent.*
import dev.ghostflyby.mcp.message
import dev.ghostflyby.mcp.rest.markdown.TextBody
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import java.nio.file.DirectoryNotEmptyException

internal fun Route.fileWriteRoutes() {
    put<Api.FilesEntry.File> { resource: Api.FilesEntry.File ->
        val target = call.resolveWritableSessionRouteTarget(resource.path.toRoutePath())
            ?: return@put
        handleSessionPut(
            call,
            target,
            resource.parent.force,
        )
    }
    post<Api.FilesEntry.File> { resource: Api.FilesEntry.File ->
        val target = call.resolveWritableSessionRouteTarget(resource.path.toRoutePath())
            ?: return@post
        handleSessionPost(
            call,
            target,
            resource.parent.force,
        )
    }
    delete<Api.FilesEntry.File> { resource: Api.FilesEntry.File ->
        val target = call.resolveWritableSessionRouteTarget(resource.path.toRoutePath())
            ?: return@delete
        handleSessionDelete(
            call,
            target,
            resource.parent.force,
        )
    }
}

// ── Dispatchers ─────────────────────────────────────────────
internal suspend fun handleSessionPut(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    force: Boolean,
) {
    projectExec(call, target, force) { access, project, body, force ->
        if (access.targetIsBinary) return@projectExec WriteResult.Unsupported("Binary writes are disabled in this phase")
        writeGate(access, FileContentKind.PUT, force)?.let { return@projectExec it }
        val file = access.file
        if (file != null) {
            @Suppress("UnstableApiUsage")
            writeCommandAction(project, message("command.name.rest.put")) {
                setTextWithPolicy(file, project, body)
            }
            WriteResult.Replaced(file)
        } else {
            @Suppress("UnstableApiUsage")
            val created = writeCommandAction(project, message("command.name.rest.put")) {
                createNewChildFile(access, project, body)
            }
            WriteResult.Created(created)
        }
    }
}

internal suspend fun handleSessionPost(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    force: Boolean,
) {
    projectExec(call, target, force) { access, project, body, force ->
        if (access.targetIsBinary) return@projectExec WriteResult.Unsupported("Binary writes are disabled in this phase")
        writeGate(access, FileContentKind.PUT, force)?.let { return@projectExec it }
        val file = access.file
        if (file != null) return@projectExec WriteResult.Conflict
        if (body.isEmpty()) {
            val dir = createDir(access) ?: return@projectExec WriteResult.Conflict
            return@projectExec WriteResult.DirCreated(dir)
        }
        @Suppress("UnstableApiUsage")
        val created = writeCommandAction(project, message("command.name.rest.post")) {
            createNewChildFile(access, project, body)
        }
        WriteResult.Created(created)
    }
}

internal suspend fun handleSessionDelete(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    force: Boolean,
) {
    projectExec(call, target, force) { access, project, _, force ->
        val file = access.file ?: return@projectExec WriteResult.NotFound
        if (!file.isDirectory && file.fileType.isBinary) {
            return@projectExec WriteResult.Unsupported("Binary deletes are disabled in this phase")
        }
        writeGate(access, FileContentKind.DELETE, force)?.let { return@projectExec it }
        runCatching {
            deleteFileWithRefactoring(project, file, access.relativePath, force)
        }.fold(
            onSuccess = { result ->
                when (result) {
                    is DeleteRefactoringResult.Deleted -> WriteResult.Deleted(result.path, result.references)
                    is DeleteRefactoringResult.BlockedByReferences -> WriteResult.DeleteBlocked(
                        result.path,
                        result.references,
                    )
                }
            },
            onFailure = { e ->
                if (e is DirectoryNotEmptyException) WriteResult.NotEmpty
                else WriteResult.RefactoringFailed(access.relativePath, e.message ?: "Delete refactoring failed")
            },
        )
    }
}

private suspend fun projectExec(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    force: Boolean,
    op: suspend (ProjectFileAccess, Project, String, Boolean) -> WriteResult,
) {
    val access = resolveProjectFileAccess(target.project, target.root, target.relativePath)
    val body = call.receiveText()
    respondResult(call, op(access, target.project, body, force), force)
}

private suspend fun respondResult(call: ApplicationCall, result: WriteResult, force: Boolean = false) {
    when (result) {
        is WriteResult.Created -> call.respond(HttpStatusCode.Created, writeResponse(result.file.url))
        is WriteResult.DirCreated -> call.respond(HttpStatusCode.Created, writeResponse(result.dir.url))
        is WriteResult.Replaced -> call.respond(HttpStatusCode.OK, writeResponse(result.file.url))
        is WriteResult.Deleted -> call.respond(
            HttpStatusCode.OK,
            DeleteResponse(path = result.path, deleted = true, references = result.references),
        )

        is WriteResult.DeleteBlocked -> call.respond(
            HttpStatusCode.Conflict,
            DeleteResponse(
                path = result.path,
                deleted = false,
                references = result.references,
                error = "References found; retry with force=true to delete",
            ),
        )

        is WriteResult.RefactoringFailed -> call.respond(
            HttpStatusCode.Conflict,
            DeleteResponse(path = result.path, deleted = false, error = result.reason),
        )

        is WriteResult.Conflict -> call.respond(
            HttpStatusCode.Conflict,
            WriteResponse(error = "Resource already exists"),
        )

        is WriteResult.NotFound -> call.respond(HttpStatusCode.NotFound, WriteResponse(error = "Resource not found"))
        is WriteResult.NotEmpty -> call.respond(HttpStatusCode.Conflict, WriteResponse(error = "Directory not empty"))
        is WriteResult.Forbidden -> call.respond(
            HttpStatusCode.Forbidden,
            WriteResponse(error = result.reason, force = "$force"),
        )

        is WriteResult.Unsupported -> call.respond(
            HttpStatusCode.UnsupportedMediaType,
            WriteResponse(error = result.reason),
        )
    }
}

private fun writeResponse(uri: String): WriteResponse = WriteResponse(
    uri = uri,
    encodedUri = encodeRoutePathSegment(uri),
)

@Serializable
private data class WriteResponse(
    val uri: String? = null,
    val encodedUri: String? = null,
    val error: String? = null,
    val force: String? = null,
) : TextBody {
    override fun renderTextBody(): String = when {
        error != null && force != null -> "$error\nforce: $force\n"
        error != null -> "$error\n"
        uri != null -> "uri: $uri\nencodedUri: $encodedUri\n"
        else -> ""
    }
}

private sealed interface WriteResult {
    data class Created(val file: VirtualFile) : WriteResult
    data class DirCreated(val dir: VirtualFile) : WriteResult
    data class Replaced(val file: VirtualFile) : WriteResult
    data class Deleted(val path: String, val references: List<FileRefactoringReference>) : WriteResult
    data class DeleteBlocked(val path: String, val references: List<FileRefactoringReference>) : WriteResult
    data class RefactoringFailed(val path: String, val reason: String) : WriteResult
    data object Conflict : WriteResult
    data object NotFound : WriteResult
    data object NotEmpty : WriteResult
    data class Forbidden(val reason: String) : WriteResult
    data class Unsupported(val reason: String) : WriteResult
}

@Serializable
private data class DeleteResponse(
    val path: String,
    val deleted: Boolean,
    val references: List<FileRefactoringReference> = emptyList(),
    val error: String? = null,
) : TextBody {
    override fun renderTextBody(): String = buildString {
        appendLine("---")
        appendLine("path: ${yamlScalar(path)}")
        appendLine("deleted: $deleted")
        appendLine("referenceCount: ${references.size}")
        error?.let { appendLine("error: ${yamlScalar(it)}") }
        appendLine("---")
        error?.let {
            appendLine("ERROR: $it")
            appendLine()
        }
        appendRefactoringReferences(references)
    }
}

// ── Implementations ─────────────────────────────────────────
private fun writeGate(
    access: ProjectFileAccess,
    kind: FileContentKind,
    force: Boolean,
): WriteResult? {
    if (access.policy.classification in setOf(
            FileContentClassification.EXCLUDED,
            FileContentClassification.OUTSIDE_PROJECT,
            FileContentClassification.DEPENDENCY_OR_SDK,
        )
    ) {
        return WriteResult.Forbidden(access.policy.reason)
    }
    if (!access.policy.canWrite(kind, force)) return WriteResult.Forbidden(access.policy.reason)
    return null
}

private suspend fun createDir(access: ProjectFileAccess): VirtualFile? {
    if (access.file != null) return null
    val parent = access.parent ?: return null
    val targetName = access.targetName ?: return null
    return backgroundWriteAction { parent.createChildDirectory(Any(), targetName) }
}

private fun createNewChildFile(access: ProjectFileAccess, project: Project, body: String): VirtualFile {
    val p = access.parent ?: error("Parent not found: ${access.relativePath}")
    val n = access.targetName ?: error("Target name not found: ${access.relativePath}")
    val vf = p.createChildData(Any(), n)
    setTextWithPolicy(vf, project, body)
    return vf
}

@RequiresWriteLock
@RequiresReadLock
internal fun setTextWithPolicy(
    file: VirtualFile,
    project: Project,
    text: String,
) {
    val doc = getOrCreateDocument(file)
    if (doc != null) {
        doc.setText(text)
        val mgr = PsiDocumentManager.getInstance(project)
        mgr.doPostponedOperationsAndUnblockDocument(doc)
        mgr.commitDocument(doc)
    } else {
        file.setBinaryContent(text.toByteArray(Charsets.UTF_8))
    }
}
