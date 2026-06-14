package dev.ghostflyby.mcp.patch

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument

internal suspend fun createPatchedFile(project: Project, target: ProjectPatchPath, text: String) {
    edtWriteAction {
        if (service<VirtualFileManager>().findFileByUrl(target.url) != null) throw WorkspaceResourceException("exists")
        val parentPath = target.nioPath.parent ?: throw WorkspaceResourceException("no parent")
        val parent = VfsUtil.createDirectories(parentPath.toString())
        val file = parent.createChildData("patch", target.nioPath.fileName.toString())
        val doc = getOrCreateDocument(file) ?: throw WorkspaceResourceException("no doc")
        doc.setText(text)
        commitDocument(project, doc)
    }
}

internal suspend fun deletePatchedFile(target: ProjectPatchPath, hunks: List<PatchHunk>) {
    val (file, document) = resolveTextDocumentForTool(target)
    ensureFileWritable(file)
    if (hunks.isNotEmpty()) GenericPatchApplier.apply(document.immutableCharSequence, hunks)
        ?: throw WorkspaceResourceException("...")
    edtWriteAction { file.delete(Any()) }
}

internal suspend fun resolveTextDocumentForTool(target: ProjectPatchPath): Pair<VirtualFile, Document> = readAction {
    val vfs = service<VirtualFileManager>()
    val file = vfs.findFileByUrl(target.url) ?: throw WorkspaceResourceException("not found")
    if (file.isDirectory) throw WorkspaceResourceException("is dir")
    val doc = getOrCreateDocument(file) ?: throw WorkspaceResourceException("no doc")
    file to doc
}

internal suspend fun ensureFileWritable(file: VirtualFile) {
    if (!readAction { file.isWritable }) throw WorkspaceResourceException("not writable")
}

internal fun commitDocument(project: Project, document: Document) {
    val mgr = PsiDocumentManager.getInstance(project)
    mgr.doPostponedOperationsAndUnblockDocument(document)
    mgr.commitDocument(document)
}
