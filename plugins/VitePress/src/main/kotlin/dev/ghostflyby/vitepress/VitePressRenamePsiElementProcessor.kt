/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.vitepress

import com.intellij.injected.editor.DocumentWindow
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiFileRange
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.refactoring.listeners.RefactoringElementListener
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.usageView.UsageInfo

internal class VitePressRenamePsiElementProcessor : RenamePsiElementProcessor() {

    override fun canProcessElement(element: PsiElement): Boolean {
        return findReferences(
            element,
            GlobalSearchScope.projectScope(element.project),
            false,
        ).any(::isVitePressInjectedReference)
    }

    override fun renameElement(
        element: PsiElement,
        newName: String,
        usages: Array<out UsageInfo>,
        listener: RefactoringElementListener?,
    ) {
        val (_, otherUsages) = usages.partition(::isVitePressInjectedUsage)
        val delegate =
            allForElement(element)
                .firstOrNull { it !== this }

        if (delegate != null) {
            delegate.renameElement(element, newName, otherUsages.toTypedArray(), listener)
        } else {
            super.renameElement(element, newName, otherUsages.toTypedArray(), listener)
        }
    }

    override fun getPostRenameCallback(
        element: PsiElement,
        newName: String,
        usages: Collection<UsageInfo>,
        allRenames: Map<PsiElement, String>,
        elementListener: RefactoringElementListener,
    ): Runnable? {
        val (vitePressUsages, otherUsages) = usages.partition(::isVitePressInjectedUsage)
        val delegate =
            allForElement(element)
                .firstOrNull { it !== this }
        val delegateCallback =
            delegate?.getPostRenameCallback(element, newName, otherUsages, allRenames, elementListener)
                ?: super.getPostRenameCallback(element, newName, otherUsages, allRenames, elementListener)
        if (vitePressUsages.isEmpty()) {
            return delegateCallback
        }
        val hostRenamePointers = createHostRenamePointers(vitePressUsages)
        val vitePressCallback = Runnable {
            try {
                applyHostRenamePointers(element.project, hostRenamePointers, newName)
            } finally {
                val smartPointerManager = SmartPointerManager.getInstance(element.project)
                hostRenamePointers.forEach(smartPointerManager::removePointer)
            }
        }
        if (delegateCallback == null) {
            return vitePressCallback
        }
        return Runnable {
            delegateCallback.run()
            vitePressCallback.run()
        }
    }

    private fun isVitePressInjectedUsage(usage: UsageInfo): Boolean {
        return usage.reference?.let(::isVitePressInjectedReference) == true
    }

    private fun isVitePressInjectedReference(reference: PsiReference): Boolean {
        val referenceElement = reference.element
        val injectedLanguageManager = InjectedLanguageManager.getInstance(referenceElement.project)
        val containingFile = referenceElement.containingFile
        val topLevelFile =
            if (injectedLanguageManager.isInjectedFragment(containingFile)) {
                injectedLanguageManager.getTopLevelFile(referenceElement)
            } else {
                containingFile
            }
        val viewProvider = topLevelFile.viewProvider
        return viewProvider is VitePressFileViewProvider &&
                (viewProvider.baseLanguage == VitePressLanguage || topLevelFile.fileType == VitePressFiletype)
    }

    private fun createHostRenamePointers(usages: List<UsageInfo>): List<SmartPsiFileRange> {
        return usages
            .mapNotNull(::toHostRenameTarget)
            .distinctBy { target -> target.file.virtualFile?.path to target.range }
            .map { target ->
                SmartPointerManager.getInstance(target.file.project)
                    .createSmartPsiFileRangePointer(target.file, target.range)
            }
    }

    private fun applyHostRenamePointers(
        project: com.intellij.openapi.project.Project,
        pointers: List<SmartPsiFileRange>,
        newName: String,
    ) {
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val injectedLanguageManager = InjectedLanguageManager.getInstance(project)
        runWriteAction {
            pointers.forEach { pointer ->
                val hostFile = pointer.containingFile ?: return@forEach
                val hostRange = pointer.psiRange ?: pointer.range ?: return@forEach
                val document = psiDocumentManager.getDocument(hostFile)
                if (document != null) {
                    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
                    psiDocumentManager.commitDocument(document)
                }
                val reference = findReferenceAtHostRange(hostFile, TextRange.create(hostRange), injectedLanguageManager)
                    ?: return@forEach
                reference.handleElementRename(newName)
                if (document != null) {
                    psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
                    psiDocumentManager.commitDocument(document)
                }
            }
        }
    }

    private fun findReferenceAtHostRange(
        hostFile: PsiFile,
        hostRange: TextRange,
        injectedLanguageManager: InjectedLanguageManager,
    ): PsiReference? {
        val injectedElement = injectedLanguageManager.findInjectedElementAt(hostFile, hostRange.startOffset)
        if (injectedElement != null) {
            findReferenceInParents(injectedElement, hostFile, hostRange)?.let { return it }
        }
        return hostFile.findReferenceAt(hostRange.startOffset)
            ?.takeIf { reference -> matchesHostRange(reference, hostFile, hostRange) }
    }

    private fun findReferenceInParents(
        startElement: PsiElement,
        hostFile: PsiFile,
        hostRange: TextRange,
    ): PsiReference? {
        var current: PsiElement? = startElement
        while (current != null && current != hostFile) {
            current.references
                .firstOrNull { reference -> matchesHostRange(reference, hostFile, hostRange) }
                ?.let { return it }
            current = current.parent
        }
        return null
    }

    private fun matchesHostRange(reference: PsiReference, hostFile: PsiFile, hostRange: TextRange): Boolean {
        val target = toHostRenameTarget(reference) ?: return false
        return target.file.virtualFile == hostFile.virtualFile && target.range == hostRange
    }

    private fun toHostRenameTarget(usage: UsageInfo): HostRenameTarget? {
        val reference = usage.reference ?: return null
        return toHostRenameTarget(reference)
    }

    private fun toHostRenameTarget(reference: PsiReference): HostRenameTarget? {
        val referenceElement = reference.element
        val injectedFile = referenceElement.containingFile
        val injectedLanguageManager = InjectedLanguageManager.getInstance(referenceElement.project)
        val hostFile = injectedLanguageManager.getTopLevelFile(referenceElement)
        val rangeInInjectedFile = reference.rangeInElement.shiftRight(referenceElement.textRange.startOffset)
        if (injectedFile == hostFile) {
            return HostRenameTarget(hostFile, rangeInInjectedFile)
        }

        val injectedDocument =
            PsiDocumentManager.getInstance(referenceElement.project).getDocument(injectedFile) as? DocumentWindow
                ?: return null
        val startHostRange =
            injectedDocument.getHostRange(injectedDocument.injectedToHost(rangeInInjectedFile.startOffset))
                ?: return null
        val endHostRange = injectedDocument.getHostRange(injectedDocument.injectedToHost(rangeInInjectedFile.endOffset))
            ?: return null
        if (startHostRange != endHostRange) {
            return null
        }
        return HostRenameTarget(hostFile, injectedDocument.injectedToHost(rangeInInjectedFile))
    }
}

private data class HostRenameTarget(
    val file: PsiFile,
    val range: TextRange,
)
