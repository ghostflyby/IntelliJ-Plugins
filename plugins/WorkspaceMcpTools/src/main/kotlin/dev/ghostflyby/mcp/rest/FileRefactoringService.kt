package dev.ghostflyby.mcp.rest

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.patch.ProjectPatchPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.nio.file.DirectoryNotEmptyException

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
    val filePath = readAction { file.path }
    if (readAction { file.isDirectory }) {
        if (readAction { file.children.isNotEmpty() }) {
            throw DirectoryNotEmptyException(filePath)
        }
        backgroundWriteAction { file.delete("rest-delete") }
        return DeleteRefactoringResult.Deleted(relativePath, emptyList())
    }

    val psiFile = readAction {
        PsiManager.getInstance(project).findFile(file)
    } ?: error("PSI file not found: $filePath")

    val refactoring = smartReadAction(project) {
        RefactoringFactory.getInstance(project).createSafeDelete(arrayOf(psiFile)).apply {
            setInteractive(null)
            isPreviewUsages = false
        }
    }

    val usages = smartReadAction(project) {
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
        val canProceed = smartReadAction(project) {
            refactoring.preprocessUsages(refUsages)
        }
        if (!canProceed) {
            error("Safe delete was cancelled by refactoring conflict preprocessing")
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
        vfs.findFileByUrl(from.url) ?: error("not found")
    }
    if (readAction { sourceFile.isDirectory }) error("is dir")
    if (!readAction { sourceFile.isWritable }) error("not writable")
    if (readAction { vfs.findFileByUrl(to.url) != null }) error("target exists")

    val parentPath = to.nioPath.parent ?: error("no parent")
    val targetParent = VfsUtil.createDirectories(parentPath.toString())
    val targetName = to.nioPath.fileName.toString()
    val originalName = readAction { sourceFile.name }
    val needsMove = readAction { sourceFile.parent != targetParent }
    val needsRename = originalName != targetName
    val targetParentUrl = readAction { targetParent.url }

    if (needsMove) {
        val intermediateUrl = "$targetParentUrl/$originalName"
        if (needsRename && readAction { vfs.findFileByUrl(intermediateUrl) != null }) {
            error("intermediate target exists: $intermediateUrl")
        }
        runMoveRefactoring(project, sourceFile, targetParent)
    }

    if (needsRename) {
        val movedFile = readAction {
            vfs.findFileByUrl(if (needsMove) "$targetParentUrl/$originalName" else from.url)
                ?: error("moved file not found")
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
            ?: error("PSI file not found: ${sourceFile.path}")
        val directory = psiManager.findDirectory(targetParent)
            ?: error("PSI directory not found: ${targetParent.path}")
        file to directory
    }
    val processor = smartReadAction(project) {
        RestMoveFilesOrDirectoriesProcessor(
            project,
            arrayOf(psiFile),
            psiDirectory,
            searchInComments = true,
            searchInNonJavaFiles = true,
        ).apply {
            prepareSuccessfulSwingThreadCallback = null
            @Suppress("UsePropertyAccessSyntax") setPreviewUsages(false)
        }
    }
    val usages = smartReadAction(project) {
        runCatching { processor.findUsagesForRest() }
            .getOrElse { error(it.message ?: "Move usage search failed") }
    }
    val refUsages = Ref(usages)
    val canProceed = readAction {
        processor.preprocessUsagesForRest(refUsages)
    }
    if (!canProceed) {
        error("Move refactoring was cancelled by conflict preprocessing")
    }
    withContext(Dispatchers.EDT) {
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
            ?: error("PSI file not found: ${sourceFile.path}")
    }
    val refactoring = smartReadAction(project) {
        RefactoringFactory.getInstance(project).createRename(psiFile, targetName, true, true).apply {
            setInteractive(null)
            isPreviewUsages = false
        }
    }
    val usages = smartReadAction(project) {
        refactoring.findUsages()
    }
    withContext(Dispatchers.EDT) {
        val refUsages = Ref(usages)
        val canProceed = smartReadAction(project) {
            refactoring.preprocessUsages(refUsages)
        }
        if (!canProceed) {
            error("Rename refactoring was cancelled by conflict preprocessing")
        }
        refactoring.doRefactoring(refUsages.get())
    }
}

private class RestMoveFilesOrDirectoriesProcessor(
    project: Project,
    elements: Array<PsiElement>,
    val newParent: PsiDirectory,
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

    override fun preprocessUsages(usages: Ref<Array<UsageInfo>>): Boolean {
        val conflicts = MultiMap<PsiElement, String>()
        MoveFileHandler.detectConflicts(myElementsToMove, usages.get(), newParent, conflicts)
        if (!conflicts.isEmpty) {
            error("Move conflicts: ${conflicts.entrySet().map { it.value }}")
        }
        return true
    }
    
    fun preprocessUsagesForRest(usages: Ref<Array<UsageInfo>>): Boolean = preprocessUsages(usages)

    override fun performRefactoring(usages: Array<UsageInfo>) {
        try {
            super.performRefactoring(usages)
        } catch (e: IncorrectOperationException) {
            val cause = e.cause
            if (cause is IOException) throw RuntimeException(cause)
            throw e
        }
    }

    fun executeForRest(usages: Array<UsageInfo>) = executeEx(usages)
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
