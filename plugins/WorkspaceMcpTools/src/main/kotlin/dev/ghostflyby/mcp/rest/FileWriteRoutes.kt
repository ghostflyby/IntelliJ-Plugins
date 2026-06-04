package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.filecontent.*
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import dev.ghostflyby.mcp.server.route.resources.RootFileResource
import dev.ghostflyby.mcp.server.route.resources.VfsResource
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.Route

internal fun Route.fileWriteRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    vfsWriteRoutes()
    projectWriteRoutes(resolver)
}

// ── VFS ─────────────────────────────────────────────────────

private fun Route.vfsWriteRoutes() {
    put<VfsResource> { resource: VfsResource ->
        vfsExec(call, resource.rawVfsUrl) { file, body ->
            if (file != null) {
                file.setBinaryContent(body); WriteResult.Replaced(file)
            } else WriteResult.NotFound
        }
    }
    post<VfsResource> { resource: VfsResource ->
        vfsExec(call, resource.rawVfsUrl) { file, _ ->
            if (file != null) WriteResult.Conflict else WriteResult.NotFound
        }
    }
    delete<VfsResource> { resource: VfsResource ->
        vfsExec(call, resource.rawVfsUrl) { file, _ -> deleteFileResult(file) }
    }
}

// ── Project ─────────────────────────────────────────────────

private fun Route.projectWriteRoutes(resolver: WorkspaceProjectResolver) {
    put<RootFileResource> { resource: RootFileResource ->
        projectExec(
            call,
            resolver,
            resource.projectKey,
            resource.parent.rootId,
            resource.relativePath,
        ) { access, project, body, force ->
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
    post<RootFileResource> { resource: RootFileResource ->
        projectExec(
            call,
            resolver,
            resource.projectKey,
            resource.parent.rootId,
            resource.relativePath,
        ) { access, project, body, force ->
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
    delete<RootFileResource> { resource: RootFileResource ->
        projectExec(
            call,
            resolver,
            resource.projectKey,
            resource.parent.rootId,
            resource.relativePath,
        ) { access, _, _, force ->
            val file = access.file ?: return@projectExec WriteResult.NotFound
            if (!file.isDirectory && file.fileType.isBinary) {
                return@projectExec WriteResult.Unsupported("Binary deletes are disabled in this phase")
            }
            writeGate(access, FileContentKind.DELETE, force)?.let { return@projectExec it }
            deleteFileResult(file)
        }
    }
}

// ── Dispatchers ─────────────────────────────────────────────

private suspend fun vfsExec(
    call: ApplicationCall,
    rawVfsUrl: String,
    op: suspend (VirtualFile?, ByteArray) -> WriteResult,
) {
    val file = resolveFileByRawUrlOrNull(rawVfsUrl)
    val body = call.receiveText().toByteArray(Charsets.UTF_8)
    respondResult(call, op(file, body))
}

private suspend fun projectExec(
    call: ApplicationCall,
    resolver: WorkspaceProjectResolver,
    projectKey: String,
    rootId: String,
    relativePath: String,
    op: suspend (ProjectFileAccess, Project, String, Boolean) -> WriteResult,
) {
    when (val resolved = resolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> {
            val target = call.rootRouteTargetOrNotFound(resolved.project, rootId, relativePath) ?: return
            val access = resolveProjectFileAccess(resolved.project, target.root, target.relativePath)
            val body = call.receiveText()
            val force = call.request.force()
            respondResult(call, op(access, resolved.project, body, force), force)
        }

        is WorkspaceProjectResolution.Unresolved ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to resolved.message))
    }
}

private fun ApplicationRequest.force(): Boolean =
    queryParameters["force"]?.toBooleanStrictOrNull() == true

private val RootFileResource.projectKey: String
    get() = parent.parent.parent.projectKey

private suspend fun respondResult(call: ApplicationCall, result: WriteResult, force: Boolean = false) {
    when (result) {
        is WriteResult.Created -> call.respond(HttpStatusCode.Created, mapOf("uri" to result.file.url))
        is WriteResult.DirCreated -> call.respond(HttpStatusCode.Created, mapOf("uri" to result.dir.url))
        is WriteResult.Replaced -> call.respond(HttpStatusCode.OK, mapOf("uri" to result.file.url))
        is WriteResult.Deleted -> call.respondText("true", ContentType.Text.Plain)
        is WriteResult.Conflict -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Resource already exists"))
        is WriteResult.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Resource not found"))
        is WriteResult.NotEmpty -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Directory not empty"))
        is WriteResult.Forbidden -> call.respond(
            HttpStatusCode.Forbidden,
            mapOf("error" to result.reason, "force" to "$force"),
        )

        is WriteResult.Unsupported -> call.respond(HttpStatusCode.UnsupportedMediaType, mapOf("error" to result.reason))
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
        val doc = FileDocumentManager.getInstance().getDocument(vf)
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
        val fileDocumentManager = FileDocumentManager.getInstance()
        val doc = if (policy.classification == FileContentClassification.IGNORED_TEXT && force) {
            fileDocumentManager.getCachedDocument(file)
        } else {
            fileDocumentManager.getDocument(file)
        }
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
