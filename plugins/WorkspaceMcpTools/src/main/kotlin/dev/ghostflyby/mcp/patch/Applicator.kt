package dev.ghostflyby.mcp.patch

import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.PatchHunk
import com.intellij.openapi.diff.impl.patch.apply.GenericPatchApplier
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal fun resolveProjectPatchPath(project: Project, path: String): ProjectPatchPath {
    val basePath = project.basePath ?: throw WorkspaceResourceException("...")
    val base = try { Path.of(basePath).toAbsolutePath().normalize() }
    catch (e: InvalidPathException) { throw WorkspaceResourceException("...") }
    val rawPath = filePathFromPatchTarget(path)
    val target = try {
        val parsed = Path.of(rawPath)
        if (parsed.isAbsolute) parsed.normalize() else base.resolve(parsed).normalize()
    } catch (e: InvalidPathException) { throw WorkspaceResourceException("...") }
    if (!target.startsWith(base)) throw WorkspaceResourceException("...")
    val rel = base.relativize(target).toString().replace('\\', '/')
    return ProjectPatchPath(relativePath = rel, nioPath = target, url = "file://$target")
}

internal fun filePathFromPatchTarget(path: String): String {
    if (!path.contains("://")) return path
    if (!path.startsWith("file://")) throw WorkspaceResourceException("...")
    return path.removePrefix("file://")
}

internal suspend fun createPatchedFile(project: Project, target: ProjectPatchPath, text: String) {
    edtWriteAction {
        if (service<VirtualFileManager>().findFileByUrl(target.url) != null) throw WorkspaceResourceException("exists")
        val parentPath = target.nioPath.parent ?: throw WorkspaceResourceException("no parent")
        val parent = VfsUtil.createDirectories(parentPath.toString())
        val file = parent.createChildData("patch", target.nioPath.fileName.toString())
        val doc = FileDocumentManager.getInstance().getDocument(file) ?: throw WorkspaceResourceException("no doc")
        doc.setText(text)
        commitDocument(project, doc)
    }
}

internal suspend fun deletePatchedFile(target: ProjectPatchPath, hunks: List<PatchHunk>) {
    val (file, document) = resolveTextDocumentForTool(target)
    ensureFileWritable(file, target)
    if (hunks.isNotEmpty()) GenericPatchApplier.apply(document.immutableCharSequence, hunks) ?: throw WorkspaceResourceException("...")
    edtWriteAction { file.delete(Any()) }
}

internal suspend fun resolveTextDocumentForTool(target: ProjectPatchPath): Pair<VirtualFile, Document> = readAction {
    val vfs = service<VirtualFileManager>()
    val file = vfs.findFileByUrl(target.url) ?: throw WorkspaceResourceException("not found")
    if (file.isDirectory) throw WorkspaceResourceException("is dir")
    val doc = FileDocumentManager.getInstance().getDocument(file) ?: throw WorkspaceResourceException("no doc")
    file to doc
}

internal suspend fun ensureToolWritable(target: ProjectPatchPath, document: Document) {
    if (!readAction { document.isWritable }) throw WorkspaceResourceException("not writable")
}

internal suspend fun ensureFileWritable(file: VirtualFile, target: ProjectPatchPath) {
    if (!readAction { file.isWritable }) throw WorkspaceResourceException("not writable")
}

internal fun commitDocument(project: Project, document: Document) {
    val mgr = PsiDocumentManager.getInstance(project)
    mgr.doPostponedOperationsAndUnblockDocument(document)
    mgr.commitDocument(document)
}
