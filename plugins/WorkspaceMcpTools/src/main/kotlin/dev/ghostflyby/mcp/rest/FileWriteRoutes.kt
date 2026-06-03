package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.filecontent.resolveFileByRawUrlOrNull
import dev.ghostflyby.mcp.filecontent.resolveFileByRelativePathOrNull
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.*
import java.nio.file.Path
internal fun Route.fileWriteRoutes() {
    val resolver: WorkspaceProjectResolver = service()
    vfsWriteRoutes()
    projectWriteRoutes(resolver)
}

// ── VFS ─────────────────────────────────────────────────────

private fun Route.vfsWriteRoutes() {
    put("/vfs/{rawVfsUrl...}") {
        vfsExec(call) { file, body ->
            if (file != null) { file.setBinaryContent(body); WriteResult.Replaced(file) }
            else WriteResult.NotFound
        }
    }
    post("/vfs/{rawVfsUrl...}") {
        vfsExec(call) { file, _ ->
            if (file != null) WriteResult.Conflict else WriteResult.NotFound
        }
    }
    delete("/vfs/{rawVfsUrl...}") {
        vfsExec(call) { file, _ -> deleteFileResult(file) }
    }
}

// ── Project ─────────────────────────────────────────────────

private fun Route.projectWriteRoutes(resolver: WorkspaceProjectResolver) {
    put("/projects/{projectKey}/files/{relativePath...}") {
        projectExec(call, resolver) { file, project, relPath, body ->
            if (file != null) {
                setDocText(file, project, body); WriteResult.Replaced(file)
            } else {
                WriteResult.Created(createAndWriteFile(project, relPath, body))
            }
        }
    }
    post("/projects/{projectKey}/files/{relativePath...}") {
        projectExec(call, resolver) { file, project, relPath, body ->
            if (file != null) return@projectExec WriteResult.Conflict
            if (body.isEmpty()) {
                val dir = createDir(project, relPath) ?: return@projectExec WriteResult.Conflict
                return@projectExec WriteResult.DirCreated(dir)
            }
            WriteResult.Created(createAndWriteFile(project, relPath, body))
        }
    }
    delete("/projects/{projectKey}/files/{relativePath...}") {
        projectExec(call, resolver) { file, _, _, _ -> deleteFileResult(file) }
    }
}

// ── Dispatchers ─────────────────────────────────────────────

private suspend fun vfsExec(call: ApplicationCall, op: suspend (VirtualFile?, ByteArray) -> WriteResult) {
    val rawVfsUrl = call.parameters.getAll("rawVfsUrl")?.joinToString("/") ?: return
    val file = resolveFileByRawUrlOrNull(rawVfsUrl)
    val body = call.receiveText().toByteArray(Charsets.UTF_8)
    respondResult(call, op(file, body))
}

private suspend fun projectExec(
    call: ApplicationCall, resolver: WorkspaceProjectResolver,
    op: suspend (VirtualFile?, Project, String, String) -> WriteResult,
) {
    val projectKey = call.parameters["projectKey"] ?: return
    val relativePath = call.parameters.getAll("relativePath")?.joinToString("/") ?: return
    when (val resolved = resolver.resolve(projectKey = projectKey)) {
        is WorkspaceProjectResolution.Resolved -> {
            val file = resolveFileByRelativePathOrNull(resolved.project, relativePath)
            val body = call.receiveText()
            respondResult(call, op(file, resolved.project, relativePath, body))
        }
        is WorkspaceProjectResolution.Unresolved ->
            call.respond(HttpStatusCode.NotFound, mapOf("error" to resolved.message))
    }
}

private suspend fun respondResult(call: ApplicationCall, result: WriteResult) {
    when (result) {
        is WriteResult.Created -> call.respond(HttpStatusCode.Created, mapOf("uri" to result.file.url))
        is WriteResult.DirCreated -> call.respond(HttpStatusCode.Created, mapOf("uri" to result.dir.url))
        is WriteResult.Replaced -> call.respond(HttpStatusCode.OK, mapOf("uri" to result.file.url))
        is WriteResult.Deleted -> call.respondText("true", ContentType.Text.Plain)
        is WriteResult.Conflict -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Resource already exists"))
        is WriteResult.NotFound -> call.respond(HttpStatusCode.NotFound, mapOf("error" to "Resource not found"))
        is WriteResult.NotEmpty -> call.respond(HttpStatusCode.Conflict, mapOf("error" to "Directory not empty"))
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
}

// ── Implementations ─────────────────────────────────────────

private suspend fun createAndWriteFile(project: Project, relPath: String, text: String): VirtualFile {
    val basePath = project.basePath ?: error("no basePath")
    val nioPath = Path.of(basePath, relPath).normalize()
    if (!nioPath.startsWith(Path.of(basePath))) error("path traversal: $relPath")
    val existing = readAction { service<VirtualFileManager>().findFileByUrl("file://${nioPath}") }
    if (existing != null) throw IllegalStateException("File already exists: $relPath")
    val vf = edtWriteAction {
        VfsUtil.createDirectories(nioPath.parent.toString())
        val parent = service<VirtualFileManager>().findFileByUrl("file://${nioPath.parent}")
            ?: error("parent dir not found: ${nioPath.parent}")
        val vf = parent.createChildData(Any(), nioPath.fileName.toString())
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

private suspend fun createDir(project: Project, relPath: String): VirtualFile? {
    val basePath = project.basePath ?: return null
    val nioPath = Path.of(basePath, relPath).normalize()
    return backgroundWriteAction { VfsUtil.createDirectories(nioPath.toString()) }
}

private suspend fun deleteFileResult(file: VirtualFile?): WriteResult {
    if (file == null) return WriteResult.NotFound
    if (file.isDirectory && file.children.isNotEmpty()) return WriteResult.NotEmpty
    backgroundWriteAction { file.delete(Any()) }
    return WriteResult.Deleted
}

private suspend fun setDocText(file: VirtualFile, project: Project, text: String) {
    edtWriteAction {
        val doc = FileDocumentManager.getInstance().getDocument(file)
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
