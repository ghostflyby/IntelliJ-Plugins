package dev.ghostflyby.mcp.rest

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.backgroundWriteAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.paths.PsiDynaReference
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.*
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReference
import com.intellij.psi.impl.source.resolve.reference.impl.providers.FileReferenceOwner
import com.intellij.psi.impl.source.resolve.reference.impl.providers.PsiFileReference
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.RefactoringFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFileHandler
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesUtil
import com.intellij.refactoring.rename.RenameUtil
import com.intellij.refactoring.util.CommonRefactoringUtil
import com.intellij.refactoring.util.NonCodeUsageInfo
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.containers.MultiMap
import dev.ghostflyby.mcp.filecontent.getOrCreateDocument
import dev.ghostflyby.mcp.patch.ProjectPatchPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.IOException
import java.lang.ref.Reference
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
    val move = smartReadAction(project) {
        RestSingleFileMoveRefactoring(project, psiFile, psiDirectory)
    }
    val usages = readAction { move.findUsages() }
    readAction {
        move.checkConflicts(usages)
    }
    move.execute(usages)
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

private class RestSingleFileMoveRefactoring(
    private val project: Project,
    private val psiFile: PsiFile,
    private val newParent: PsiDirectory,
) {
    fun findUsages(): RestSingleFileMoveUsages {
        val regularUsages = ReferencesSearch.search(psiFile, GlobalSearchScope.projectScope(project))
            .findAll()
            .map { RestMovedFileUsageInfo(it, psiFile) }
        val handler = MoveFileHandler.forElement(psiFile)
        val handlerUsages = handler.findUsages(
            psiFile,
            newParent,
            true,
            true,
        ) ?: emptyList()
        return RestSingleFileMoveUsages(
            allUsages = (regularUsages + handlerUsages).toTypedArray(),
            handlerUsages = handlerUsages,
        )
    }

    fun checkConflicts(usages: RestSingleFileMoveUsages) {
        val conflicts = MultiMap<PsiElement, String>()
        MoveFileHandler.detectConflicts(arrayOf(psiFile), usages.allUsages, newParent, conflicts)
        if (!conflicts.isEmpty) {
            error("Move conflicts: ${conflicts.entrySet().map { it.value }}")
        }
    }

    suspend fun execute(usages: RestSingleFileMoveUsages) {
        withContext(Dispatchers.EDT) {
            coroutineToIndicator {
                WriteCommandAction.writeCommandAction(project).withName("Move").run<RuntimeException> {
                    performMove(usages)
                }
            }
        }
    }

    private fun performMove(usages: RestSingleFileMoveUsages) {
        val codeUsages = mutableListOf<UsageInfo>()
        val nonCodeUsages = mutableListOf<NonCodeUsageInfo>()
        for (usage in usages.allUsages) {
            if (usage is NonCodeUsageInfo) {
                nonCodeUsages.add(usage)
            } else {
                codeUsages.add(usage)
            }
        }

        val movingFileNode = psiFile.node
        val oldToNewMap = mutableMapOf<PsiElement, PsiElement>()
        try {
            RestFileReferenceContext.encodeFileReferences(psiFile)
            val handler = MoveFileHandler.forElement(psiFile)
            handler.prepareMovedFile(psiFile, newParent, oldToNewMap)

            if (newParent.findFile(psiFile.name) == null) {
                MoveFilesOrDirectoriesUtil.doMoveFile(psiFile, newParent)
            }
            val movedFile = newParent.findFile(psiFile.name)
                ?: error("Moved file not found: ${newParent.virtualFile.path}/${psiFile.name}")

            val retargetableUsages = codeUsages.toTypedArray()
            CommonRefactoringUtil.sortDepthFirstRightLeftOrder(retargetableUsages)
            DumbService.getInstance(project).completeJustSubmittedTasks()

            MoveFileHandler.forElement(movedFile).updateMovedFile(movedFile)
            RestFileReferenceContext.decodeFileReferences(movedFile)
            retargetRegularUsages(retargetableUsages)
            retargetHandlerUsages(usages, oldToNewMap)

            if (nonCodeUsages.isNotEmpty()) {
                RenameUtil.renameNonCodeUsages(project, nonCodeUsages.toTypedArray())
            }
        } catch (e: IncorrectOperationException) {
            val cause = e.cause
            if (cause is IOException) throw RuntimeException(cause)
            throw e
        } finally {
            Reference.reachabilityFence(movingFileNode)
        }
    }

    private fun retargetRegularUsages(usages: Array<UsageInfo>) {
        for (usage in usages) {
            if (usage !is RestMovedFileUsageInfo) continue
            val reference = usage.psiReference
            if (reference is FileReference || reference is PsiDynaReference<*>) {
                val usageElement = usage.element
                val usageFile = usageElement?.containingFile
                val basePsiFile = usageFile?.viewProvider?.getPsi(usageFile.viewProvider.baseLanguage)
                if (basePsiFile != null && basePsiFile == usage.target) {
                    continue
                }
            }
            if (reference.element.isValid) {
                reference.bindToElement(usage.target)
            }
        }
    }

    private fun retargetHandlerUsages(
        usages: RestSingleFileMoveUsages,
        oldToNewMap: Map<PsiElement, PsiElement>,
    ) {
        val sortedUsages = usages.handlerUsages.sortedBy { usage ->
            usage.element?.textRange?.startOffset ?: -1
        }
        MoveFileHandler.forElement(psiFile).retargetUsages(sortedUsages, oldToNewMap)
    }
}

private class RestSingleFileMoveUsages(
    val allUsages: Array<UsageInfo>,
    val handlerUsages: List<UsageInfo>,
)

private class RestMovedFileUsageInfo(
    val psiReference: PsiReference,
    val target: PsiElement,
) : UsageInfo(psiReference)

private object RestFileReferenceContext {
    private val logger = Logger.getInstance(RestFileReferenceContext::class.java)
    private val fileReferenceKey = Key.create<StoredFileReference>("dev.ghostflyby.mcp.rest.fileReference")

    fun encodeFileReferences(element: PsiElement?) {
        if (element == null || element is PsiCompiledElement || element.isBinaryFileElement()) return

        element.accept(
            object : PsiRecursiveElementWalkingVisitor(true) {
                override fun visitElement(element: PsiElement) {
                    if (element is PsiLanguageInjectionHost && element.isValid) {
                        InjectedLanguageManager.getInstance(element.project)
                            .enumerate(element) { injectedPsi, _ -> encodeFileReferences(injectedPsi) }
                    }

                    val references = element.references
                    for (refIndex in references.indices) {
                        val fileReference = (references[refIndex] as? FileReferenceOwner)?.lastFileReference
                        if (fileReference != null && encodeFileReference(element, fileReference, refIndex)) break
                    }
                    super.visitElement(element)
                }
            },
        )
    }

    private fun encodeFileReference(
        element: PsiElement,
        reference: PsiFileReference,
        refIndex: Int,
    ): Boolean {
        for (result in reference.multiResolve(false)) {
            val fileSystemItem = result.element as? PsiFileSystemItem ?: continue
            element.putCopyableUserData(fileReferenceKey, StoredFileReference(fileSystemItem, refIndex))
            return true
        }
        return false
    }

    fun decodeFileReferences(element: PsiElement?) {
        if (element == null || element is PsiCompiledElement || element.isBinaryFileElement()) return

        element.accept(
            object : PsiRecursiveElementVisitor(true) {
                override fun visitElement(element: PsiElement) {
                    val storedReference = element.getCopyableUserData(fileReferenceKey)
                    element.putCopyableUserData(fileReferenceKey, null)

                    val reboundElement = bindElement(
                        element,
                        storedReference?.fileSystemItem,
                        storedReference?.refIndex ?: -1,
                    )
                    reboundElement.acceptChildren(this)

                    if (reboundElement is PsiLanguageInjectionHost) {
                        InjectedLanguageManager.getInstance(reboundElement.project)
                            .enumerate(reboundElement) { injectedPsi, _ -> decodeFileReferences(injectedPsi) }
                    }
                }
            },
        )
    }

    private fun bindElement(
        element: PsiElement,
        fileSystemItem: PsiFileSystemItem?,
        refIndex: Int,
    ): PsiElement {
        if (fileSystemItem?.isValid != true || fileSystemItem.virtualFile == null) return element

        val references = element.references
        if (refIndex >= 0 && references.size > refIndex && references[refIndex] is FileReferenceOwner) {
            bindAndCheckElement(references[refIndex], element, fileSystemItem)?.let { return it }
        }

        for (reference in references) {
            if (reference is FileReferenceOwner) {
                bindAndCheckElement(reference, element, fileSystemItem)?.let { return it }
                break
            }
        }
        return element
    }

    private fun bindAndCheckElement(
        reference: PsiReference,
        element: PsiElement,
        fileSystemItem: PsiFileSystemItem,
    ): PsiElement? {
        val fileReference = (reference as FileReferenceOwner).lastFileReference ?: return null
        val newElement = fileReference.bindToElement(fileSystemItem)
        if (newElement != null && element::class.java != newElement::class.java) {
            logger.error("Reference $reference changed ${element::class.java.name} to ${newElement::class.java.name}")
        }
        return newElement
    }

    private fun PsiElement.isBinaryFileElement(): Boolean {
        val containingFile = containingFile ?: return true
        return containingFile.fileType.isBinary
    }

    private data class StoredFileReference(
        val fileSystemItem: PsiFileSystemItem,
        val refIndex: Int,
    )
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
