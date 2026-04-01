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
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiReference
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
        val delegate =
            allForElement(element)
                .firstOrNull { it !== this }
        val delegateCallback =
            delegate?.getPostRenameCallback(element, newName, usages, allRenames, elementListener)
                ?: super.getPostRenameCallback(element, newName, usages, allRenames, elementListener)
        val vitePressUsages = usages.filter(::isVitePressInjectedUsage)
        if (vitePressUsages.isEmpty()) {
            return delegateCallback
        }
        val hostRenameMarkers = createHostRenameMarkers(vitePressUsages)
        val vitePressCallback = Runnable {
            try {
                applyHostRenameMarkers(element.project, hostRenameMarkers, newName)
            } finally {
                hostRenameMarkers.forEach { marker -> marker.rangeMarker.dispose() }
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

    private fun createHostRenameMarkers(usages: List<UsageInfo>): List<HostRenameMarker> {
        return usages
            .mapNotNull(::toHostRenameTarget)
            .distinctBy { target -> target.file.virtualFile?.path to target.range }
            .map { target ->
                val psiDocumentManager = PsiDocumentManager.getInstance(target.file.project)
                val document = requireNotNull(psiDocumentManager.getDocument(target.file))
                HostRenameMarker(
                    document = document,
                    rangeMarker = document.createRangeMarker(target.range),
                )
            }
    }

    private fun applyHostRenameMarkers(
        project: com.intellij.openapi.project.Project,
        markers: List<HostRenameMarker>,
        newName: String,
    ) {
        val psiDocumentManager = PsiDocumentManager.getInstance(project)
        val documentsToCommit = LinkedHashSet<Document>()
        markers
            .filter { marker -> marker.rangeMarker.isValid }
            .sortedByDescending { marker -> marker.rangeMarker.startOffset }
            .forEach { marker ->
                psiDocumentManager.doPostponedOperationsAndUnblockDocument(marker.document)
                marker.document.replaceString(marker.rangeMarker.startOffset, marker.rangeMarker.endOffset, newName)
                documentsToCommit += marker.document
            }

        documentsToCommit.forEach { document ->
            psiDocumentManager.doPostponedOperationsAndUnblockDocument(document)
            psiDocumentManager.commitDocument(document)
        }
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

private data class HostRenameMarker(
    val document: Document,
    val rangeMarker: RangeMarker,
)
