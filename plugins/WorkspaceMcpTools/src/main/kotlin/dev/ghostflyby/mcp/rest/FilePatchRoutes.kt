package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.*
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mcp.filecontent.resolveFileByRelativePathOrNull
import dev.ghostflyby.mcp.filecontent.tools.*
import dev.ghostflyby.mcp.filecontent.tools.*
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolution
import dev.ghostflyby.mcp.sdk.WorkspaceProjectResolver
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

@Serializable
private data class PatchResponse(
    val applied: List<Map<String, String>>,
    val failed: List<String> = emptyList(),
)

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
                val sections = splitCodexByFile(call.receiveText())
                    if (sections.isEmpty()) {
                    call.respond(HttpStatusCode.BadRequest, PatchResponse(applied = emptyList(), failed = listOf("No patch sections found")))
                    return@patch
                }
                val applied = mutableListOf<Map<String, String>>()
                val failed = mutableListOf<String>()
                for (section in sections) {
                    try {
                        val fileRelPath = if (isDir) "$relativePath/${section.filePath}" else section.filePath
                        if (!isDir && section.filePath != relativePath)
                            throw IllegalArgumentException("Section targets '${section.filePath}' but PATCH target is '$relativePath'")
                        val target = resolveProjectPatchPath(project, fileRelPath)
                        when (section.operation) {
                            CodexFileOperation.ADD -> {
                                createPatchedFile(project, target, parseCodexAddContent(section.rawLines))
                                applied += mapOf("path" to fileRelPath, "operation" to "add")
                            }
                            CodexFileOperation.DELETE -> {
                                deletePatchedFile(target, emptyList())
                                applied += mapOf("path" to fileRelPath, "operation" to "delete")
                            }
                            CodexFileOperation.UPDATE -> {
                                val raw = section.rawLines
                                edtWriteAction {
                                    val vf = VirtualFileManager.getInstance().findFileByUrl(target.url)
                                        ?: error("File not found: ${target.relativePath}")
                                    val doc = FileDocumentManager.getInstance().getDocument(vf)
                                        ?: error("Binary or no text: ${target.relativePath}")
                                    if (!doc.isWritable) error("Not writable: ${target.relativePath}")
                                    val hunks = parseCodexRawHunks(raw, doc.immutableCharSequence)
                                        ?: error("No valid hunks")
                                    val appliedPatch = GenericPatchApplier.apply(doc.immutableCharSequence, hunks)
                                        ?: error("Patch does not apply for ${target.relativePath}")
                                    doc.setText(appliedPatch.patchedText)
                                    val mgr = com.intellij.psi.PsiDocumentManager.getInstance(project)
                                    mgr.doPostponedOperationsAndUnblockDocument(doc)
                                    mgr.commitDocument(doc)
                                }
                                applied += mapOf("path" to fileRelPath, "operation" to "update")
                            }
                        }
                    } catch (e: Exception) {
                        failed += "${section.filePath}: ${e.message ?: "unknown error"}"
                    }
                }
                call.respond(HttpStatusCode.OK, PatchResponse(applied = applied, failed = failed))
            }
            is WorkspaceProjectResolution.Unresolved ->
                call.respond(HttpStatusCode.NotFound, PatchResponse(applied = emptyList(), failed = listOf(resolved.message)))
        }
    }
}
