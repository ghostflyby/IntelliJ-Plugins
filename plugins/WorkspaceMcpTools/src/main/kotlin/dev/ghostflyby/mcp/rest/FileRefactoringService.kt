package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import dev.ghostflyby.mcp.common.WorkspaceResourceException
import dev.ghostflyby.mcp.common.relativizePathOrOriginal
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.patch.ProjectPatchPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

internal sealed interface DeleteRefactoringResult {
    val references: List<FileRefactoringReference>

    data class Deleted(
        val path: String,
        override val references: List<FileRefactoringReference>,
    ) : DeleteRefactoringResult

    data class BlockedByReferences(
        val path: String,
        override val references: List<FileRefactoringReference>,
    ) : DeleteRefactoringResult
}

@Serializable
internal data class FileRefactoringReference(
    val filePath: String,
    val fileUrl: String,
    val encodedFileUrl: String,
    val line: Int,
    val column: Int,
    val usageText: String,
)

internal suspend fun deleteFileWithRefactoring(
    project: Project,
    file: VirtualFile,
    relativePath: String,
    force: Boolean,
): DeleteRefactoringResult {
    if (file.isDirectory) {
        if (readAction { file.children.isNotEmpty() }) {
            throw WorkspaceResourceException("Directory not empty")
        }
        edtWriteAction { file.delete("rest-delete") }
        return DeleteRefactoringResult.Deleted(relativePath, emptyList())
    }

    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(file)
    } ?: throw WorkspaceResourceException("PSI file not found: ${file.path}")

    val refactoring = RefactoringFactory.getInstance(project).createSafeDelete(arrayOf(psiFile))
    refactoring.setInteractive(null)
    refactoring.isPreviewUsages = false

    val usages = runSmartRead(project) {
        refactoring.findUsages()
    }
    val references = readAction {
        usages.mapNotNull { it.toFileRefactoringReference(project.basePath) }
            .distinctBy { "${it.fileUrl}:${it.line}:${it.column}:${it.usageText}" }
    }

    if (references.isNotEmpty() && !force) {
        return DeleteRefactoringResult.BlockedByReferences(relativePath, references)
    }

    withContext(Dispatchers.EDT) {
        val refUsages = Ref(usages)
        val canProceed = refactoring.preprocessUsages(refUsages)
        if (!canProceed) {
            throw WorkspaceResourceException("Safe delete was cancelled by refactoring conflict preprocessing")
        }
        refactoring.doRefactoring(refUsages.get())
    }
    return DeleteRefactoringResult.Deleted(relativePath, references)
}

internal suspend fun moveFileWithRefactoring(
    project: Project,
    from: ProjectPatchPath,
    to: ProjectPatchPath,
) {
    val vfs = VirtualFileManager.getInstance()
    val sourceFile = readAction {
        vfs.findFileByUrl(from.url) ?: throw WorkspaceResourceException("not found")
    }
    if (readAction { sourceFile.isDirectory }) throw WorkspaceResourceException("is dir")
    if (!readAction { sourceFile.isWritable }) throw WorkspaceResourceException("not writable")
    if (readAction { vfs.findFileByUrl(to.url) != null }) throw WorkspaceResourceException("target exists")

    val targetParent = edtWriteAction {
        val parentPath = to.nioPath.parent ?: throw WorkspaceResourceException("no parent")
        VfsUtil.createDirectories(parentPath.toString())
    }
    val targetName = to.nioPath.fileName.toString()
    val originalName = readAction { sourceFile.name }
    val needsMove = readAction { sourceFile.parent != targetParent }
    val needsRename = originalName != targetName

    if (needsMove) {
        val intermediateUrl = "${targetParent.url}/$originalName"
        if (needsRename && readAction { vfs.findFileByUrl(intermediateUrl) != null }) {
            throw WorkspaceResourceException("intermediate target exists: $intermediateUrl")
        }
        runMoveRefactoring(project, sourceFile, targetParent)
    }

    if (needsRename) {
        val movedFile = readAction {
            vfs.findFileByUrl(if (needsMove) "${targetParent.url}/$originalName" else from.url)
                ?: throw WorkspaceResourceException("moved file not found")
        }
        runRenameRefactoring(project, movedFile, targetName)
    }
}

private suspend fun runMoveRefactoring(
    project: Project,
    sourceFile: VirtualFile,
    targetParent: VirtualFile,
) {
    val (psiFile, psiDirectory) = readAction {
        val psiManager = PsiManager.getInstance(project)
        val file = psiManager.findFile(sourceFile)
            ?: throw WorkspaceResourceException("PSI file not found: ${sourceFile.path}")
        val directory = psiManager.findDirectory(targetParent)
            ?: throw WorkspaceResourceException("PSI directory not found: ${targetParent.path}")
        file to directory
    }
    runRefactoringProcessor(project) {
        val processor = RestMoveFilesOrDirectoriesProcessor(
            project,
            arrayOf<PsiElement>(psiFile),
            psiDirectory,
            true,
            true,
        ).apply {
            prepareSuccessfulSwingThreadCallback = null
            @Suppress("UsePropertyAccessSyntax") setPreviewUsages(false)
        }
        val usages = runCatching { processor.findUsagesForRest() }
            .getOrElse { throw WorkspaceResourceException(it.message ?: "Move usage search failed") }
        val refUsages = Ref(usages)
        val canProceed = processor.preprocessUsagesForRest(refUsages)
        if (!canProceed) {
            throw WorkspaceResourceException("Move refactoring was cancelled by conflict preprocessing")
        }
        processor.executeForRest(refUsages.get())
    }
}

private suspend fun runRenameRefactoring(
    project: Project,
    sourceFile: VirtualFile,
    targetName: String,
) {
    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(sourceFile)
            ?: throw WorkspaceResourceException("PSI file not found: ${sourceFile.path}")
    }
    val refactoring = RefactoringFactory.getInstance(project).createRename(psiFile, targetName, true, true)
    refactoring.setInteractive(null)
    refactoring.isPreviewUsages = false
    val usages = runSmartRead(project) {
        refactoring.findUsages()
    }
    withContext(Dispatchers.EDT) {
        val refUsages = Ref(usages)
        val canProceed = refactoring.preprocessUsages(refUsages)
        if (!canProceed) {
            throw WorkspaceResourceException("Rename refactoring was cancelled by conflict preprocessing")
        }
        refactoring.doRefactoring(refUsages.get())
    }
}

private class RestMoveFilesOrDirectoriesProcessor(
    project: Project,
    elements: Array<PsiElement>,
    newParent: com.intellij.psi.PsiDirectory,
    searchInComments: Boolean,
    searchInNonJavaFiles: Boolean,
) : MoveFilesOrDirectoriesProcessor(
    project,
    elements,
    newParent,
    searchInComments,
    searchInNonJavaFiles,
    null,
    null,
) {
    fun findUsagesForRest(): Array<UsageInfo> = findUsages()

    fun preprocessUsagesForRest(usages: Ref<Array<UsageInfo>>): Boolean = preprocessUsages(usages)

    fun executeForRest(usages: Array<UsageInfo>) = executeEx(usages)
}

private suspend fun runRefactoringProcessor(project: Project, block: () -> Unit) {
    withContext(Dispatchers.EDT) {
        if (DumbService.isDumb(project)) {
            throw WorkspaceResourceException("Refactoring is unavailable while indexes are updating")
        }
        block()
    }
}

private suspend fun <T> runSmartRead(project: Project, block: () -> T): T {
    if (DumbService.isDumb(project)) {
        throw WorkspaceResourceException("Refactoring is unavailable while indexes are updating")
    }
    return readAction {
        ProgressManager.checkCanceled()
        block()
    }
}

private fun UsageInfo.toFileRefactoringReference(projectBasePath: String?): FileRefactoringReference? {
    val file = virtualFile ?: return null
    val range = navigationRange ?: return null
    val document = getOrCreateDocument(file) ?: return null
    val startOffset = range.startOffset.coerceIn(0, document.textLength)
    val lineIndex = document.getLineNumber(startOffset)
    val lineStart = document.getLineStartOffset(lineIndex)
    val lineEnd = document.getLineEndOffset(lineIndex)
    val fileUrl = file.url
    return FileRefactoringReference(
        filePath = relativizePathOrOriginal(projectBasePath, file.path),
        fileUrl = fileUrl,
        encodedFileUrl = encodeRoutePathSegment(fileUrl),
        line = lineIndex + 1,
        column = startOffset - lineStart + 1,
        usageText = document.getText(com.intellij.openapi.util.TextRange(lineStart, lineEnd)).trim().take(240),
    )
}
