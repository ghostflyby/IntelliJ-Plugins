package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.filecontent.*
import dev.ghostflyby.mcp.rest.markdown.TextBody
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

internal fun Route.fileWriteRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    val sessions: RestSessionService = service()
    put<Api.FilesEntry.File> { resource: Api.FilesEntry.File ->
        val target = call.resolveWritableFileRouteTarget(sessions, resolver, resource.path.toRoutePath())
            ?: return@put
        handleSessionPut(
            call,
            target,
            resource.parent.force,
        )
    }
    post<Api.FilesEntry.File> { resource: Api.FilesEntry.File ->
        val target = call.resolveWritableFileRouteTarget(sessions, resolver, resource.path.toRoutePath())
            ?: return@post
        handleSessionPost(
            call,
            target,
            resource.parent.force,
        )
    }
    delete<Api.FilesEntry.File> { resource: Api.FilesEntry.File ->
        val target = call.resolveWritableFileRouteTarget(sessions, resolver, resource.path.toRoutePath())
            ?: return@delete
        handleSessionDelete(
            call,
            target,
            resource.parent.force,
        )
    }
}

private suspend fun ApplicationCall.resolveWritableFileRouteTarget(
    sessions: RestSessionService,
    resolver: WorkspaceProjectResolver,
    path: String,
): RestSessionRouteTarget? {
    return when (val target = resolveFileRouteTarget(sessions, resolver, path)) {
        is RestFileRouteTarget.ProjectFile -> target.target
        is RestFileRouteTarget.VirtualFileReadOnly -> {
            respond(
                HttpStatusCode.Forbidden,
                RestError("VFS URL writes are allowed only for files in the session project workspace"),
            )
            null
        }

        null -> null
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
            setTextWithPolicy(file, project, body, access.policy, force)
            WriteResult.Replaced(file)
        } else {
            WriteResult.Created(createAndWriteFile(project, access, body))
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
        WriteResult.Created(createAndWriteFile(project, access, body))
    }
}

internal suspend fun handleSessionDelete(
    call: ApplicationCall,
    target: RestSessionRouteTarget,
    force: Boolean,
) {
    projectExec(call, target, force) { access, _, _, force ->
        val file = access.file ?: return@projectExec WriteResult.NotFound
        if (!file.isDirectory && file.fileType.isBinary) {
            return@projectExec WriteResult.Unsupported("Binary deletes are disabled in this phase")
        }
        writeGate(access, FileContentKind.DELETE, force)?.let { return@projectExec it }
        deleteFileResult(file)
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
        is WriteResult.Created -> call.respond(HttpStatusCode.Created, WriteResponse(uri = result.file.url))
        is WriteResult.DirCreated -> call.respond(HttpStatusCode.Created, WriteResponse(uri = result.dir.url))
        is WriteResult.Replaced -> call.respond(HttpStatusCode.OK, WriteResponse(uri = result.file.url))
        is WriteResult.Deleted -> call.respondText("true", ContentType.Text.Plain)
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

@Serializable
private data class WriteResponse(
    val uri: String? = null,
    val error: String? = null,
    val force: String? = null,
) : TextBody {
    override fun renderTextBody(): String = when {
        error != null && force != null -> "$error\nforce: $force\n"
        error != null -> "$error\n"
        uri != null -> "uri: $uri\n"
        else -> ""
    }
}

private sealed interface WriteResult {
    data class Created(val file: VirtualFile) : WriteResult
    data class DirCreated(val dir: VirtualFile) : WriteResult
    data class Replaced(val file: VirtualFile) : WriteResult
    data object Deleted : WriteResult
    data object Conflict : WriteResult
    data object NotFound : WriteResult
    data object NotEmpty : WriteResult
    data class Forbidden(val reason: String) : WriteResult
    data class Unsupported(val reason: String) : WriteResult
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

private suspend fun createAndWriteFile(project: Project, access: ProjectFileAccess, text: String): VirtualFile {
    if (access.file != null) throw IllegalStateException("File already exists: ${access.relativePath}")
    val parent = access.parent ?: error("Parent not found: ${access.relativePath}")
    val targetName = access.targetName ?: error("Target name not found: ${access.relativePath}")
    val vf = edtWriteAction {
        val vf = parent.createChildData(Any(), targetName)
        val doc = getOrCreateDocument(vf)
        if (doc != null) {
            doc.setText(text)
            val mgr = PsiDocumentManager.getInstance(project)
            mgr.doPostponedOperationsAndUnblockDocument(doc)
            mgr.commitDocument(doc)
        } else {
            vf.setBinaryContent(text.toByteArray(Charsets.UTF_8))
        }
        vf
    }
    return vf
}

private suspend fun createDir(access: ProjectFileAccess): VirtualFile? {
    if (access.file != null) return null
    val parent = access.parent ?: return null
    val targetName = access.targetName ?: return null
    return backgroundWriteAction { parent.createChildDirectory(Any(), targetName) }
}

private suspend fun deleteFileResult(file: VirtualFile?): WriteResult {
    if (file == null) return WriteResult.NotFound
    if (file.isDirectory && file.children.isNotEmpty()) return WriteResult.NotEmpty
    backgroundWriteAction { file.delete(Any()) }
    return WriteResult.Deleted
}

private suspend fun setTextWithPolicy(
    file: VirtualFile,
    project: Project,
    text: String,
    policy: FileAccessPolicy,
    force: Boolean,
) {
    edtWriteAction {
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
}
