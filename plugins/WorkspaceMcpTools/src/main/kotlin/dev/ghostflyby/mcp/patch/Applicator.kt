package dev.ghostflyby.mcp.patch

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument

internal suspend fun createPatchedFile(project: Project, target: ProjectPatchPath, text: String) {
    edtWriteAction {
        if (service<VirtualFileManager>().findFileByUrl(target.url) != null) error("exists")
        val parentPath = target.nioPath.parent ?: error("no parent")
        val parent = VfsUtil.createDirectories(parentPath.toString())
        val file = parent.createChildData("patch", target.nioPath.fileName.toString())
        val doc = getOrCreateDocument(file) ?: error("no doc")
        doc.setText(text)
        commitDocument(project, doc)
    }
}

internal suspend fun resolveTextDocumentForTool(target: ProjectPatchPath): Pair<VirtualFile, Document> = readAction {
    val vfs = service<VirtualFileManager>()
    val file = vfs.findFileByUrl(target.url) ?: error("not found")
    if (file.isDirectory) error("is dir")
    val doc = getOrCreateDocument(file) ?: error("no doc")
    file to doc
}

internal suspend fun ensureFileWritable(file: VirtualFile) {
    if (!readAction { file.isWritable }) error("not writable")
}

internal fun commitDocument(project: Project, document: Document) {
    val mgr = PsiDocumentManager.getInstance(project)
    mgr.doPostponedOperationsAndUnblockDocument(document)
    mgr.commitDocument(document)
}
