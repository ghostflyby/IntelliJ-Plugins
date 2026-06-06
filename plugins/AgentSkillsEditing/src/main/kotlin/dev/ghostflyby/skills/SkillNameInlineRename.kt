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

package dev.ghostflyby.skills

import com.intellij.lang.ASTNode
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Pair
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.impl.PsiElementBase
import com.intellij.refactoring.rename.inplace.VariableInplaceRenamer
import com.intellij.util.IncorrectOperationException
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar
import javax.swing.Icon

internal class SkillNameInlineElement(
    private val scalar: YAMLScalar,
    private val hostFile: PsiFile,
    private val hostTextRange: TextRange,
    private val project: Project,
) : PsiElementBase(), PsiNamedElement {

    override fun getName(): String = scalar.textValue
    override fun setName(newName: String): PsiElement =
        throw IncorrectOperationException("Use renameSynthetic in SkillNameInlineRenamer")
    override fun getProject(): Project = project
    override fun getLanguage(): Language = hostFile.language
    override fun getParent(): PsiElement? = null
    override fun getChildren(): Array<PsiElement> = EMPTY_ARRAY
    override fun getContainingFile(): PsiFile = hostFile
    override fun getTextRange(): TextRange = hostTextRange
    override fun getTextOffset(): Int = hostTextRange.startOffset
    override fun getTextLength(): Int = hostTextRange.length
    override fun getText(): String =
        hostFile.text.substring(hostTextRange.startOffset, hostTextRange.endOffset)
    override fun getStartOffsetInParent(): Int = 0
    override fun textToCharArray(): CharArray = text.toCharArray()
    override fun findElementAt(offset: Int): PsiElement? = null
    override fun findReferenceAt(offset: Int): PsiReference? = null
    override fun isValid(): Boolean = scalar.isValid
    override fun isWritable(): Boolean = true
    override fun getManager(): PsiManager = PsiManager.getInstance(project)
    override fun getIcon(flags: Int): Icon? = null
    override fun getNode(): ASTNode? = null
    override fun getUseScope() = hostFile.useScope
    override fun getResolveScope() = hostFile.resolveScope
    override fun equals(other: Any?): Boolean =
        other is SkillNameInlineElement && scalar === other.scalar
    override fun hashCode(): Int = System.identityHashCode(scalar)
    override fun toString(): String = "SkillNameInlineElement(${scalar.textValue})"
}

/** Reference from the host file to our delegate, using the absolute host
 *  text range.  Since the host file spans [0, length), the range is
 *  position-agnostic — no offset calculation needed. */
internal class SkillNameInlineReference(
    hostFile: PsiFile,
    rangeInFile: TextRange,
    private val target: PsiNamedElement,
) : PsiReferenceBase<PsiElement>(hostFile, rangeInFile) {
    override fun resolve(): PsiElement = target
    override fun isReferenceTo(element: PsiElement): Boolean = element === target
    override fun handleElementRename(newElementName: String): PsiElement? = myElement
}

/**
 * Inline renamer for skill names.
 *
 * - [addReferenceAtCaret] injects one reference covering the exact scalar
 *   range, which becomes the template's PRIMARY_VARIABLE.
 * - [collectAdditionalElementsToRename] is a no-op: we have no extra
 *   string occurrences to collect, so the RenameChooser never appears.
 * - [isIdentifier] accepts kebab-case skill names.
 * - [renameSynthetic] re-discovers the scalar from the committed document
 *   and renames the parent directory via RenameProcessor.
 */
internal class SkillNameInlineRenamer(
    elementToRename: SkillNameInlineElement,
    editor: Editor,
) : VariableInplaceRenamer(elementToRename, editor, elementToRename.project) {

    private val hostRange: TextRange = elementToRename.textRange
    private val hostFile: PsiFile = elementToRename.containingFile

    override fun getVariable(): PsiNamedElement? = myElementToRename

    override fun checkLocalScope(): PsiElement? =
        PsiDocumentManager.getInstance(myProject).getPsiFile(myEditor.document)

    /** Accept skill-name-format identifiers (kebab-case). */
    override fun isIdentifier(newName: String, language: Language): Boolean =
        newName.isValidSkillName()

    /** No extra string usages — suppress VariableInplaceRenamer's default
     *  text-occurrence search which would show a RenameChooser. */
    override fun collectAdditionalElementsToRename(
        stringUsages: MutableList<in Pair<PsiElement, TextRange>>,
    ) = Unit

    /** Inject a single reference covering the exact scalar value range.
     *  Using the host file as the element avoids offset-calculation issues
     *  since the host file spans [0, length). */
    override fun addReferenceAtCaret(refs: MutableCollection<in PsiReference>) {
        refs.add(SkillNameInlineReference(hostFile, hostRange, myElementToRename))
    }

    /** After the template has updated the document, commit PSI and
     *  rename the parent skill directory to match the new name. */
    override fun renameSynthetic(newName: String) {
        PsiDocumentManager.getInstance(myProject).commitAllDocuments()
        val dir = (hostFile.skillNameScalar()?.containingFile as? YAMLFile)?.skillDirectory ?: return
        if (dir.name != newName) {
            dir.virtualFile.rename(this, newName)
        }
    }
}


/** Convenience helper: create the inline element + renamer for a scalar
 *  and start inline rename.  Shared by the rename handler and quickfix. */
internal fun performSkillNameInlineRename(
    scalar: YAMLScalar,
    editor: Editor,
    project: Project,
) {
    val injectionManager = InjectedLanguageManager.getInstance(project)
    val hostFile = injectionManager.getTopLevelFile(scalar.containingFile) ?: return
    val hostTextRange = injectionManager.injectedToHost(scalar, scalar.textRange)
    editor.caretModel.moveToOffset(hostTextRange.startOffset)
    val delegate = SkillNameInlineElement(scalar, hostFile, hostTextRange, project)
    SkillNameInlineRenamer(delegate, editor).performInplaceRename()
}
