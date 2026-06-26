/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

@file:Suppress("UnstableApiUsage")

package dev.ghostflyby.skills

import com.intellij.model.Pointer
import com.intellij.model.Symbol
import com.intellij.model.psi.PsiSymbolDeclaration
import com.intellij.model.psi.PsiSymbolDeclarationProvider
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.*
import com.intellij.psi.search.SearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.refactoring.rename.api.*
import com.intellij.refactoring.rename.symbol.RenameableSymbol
import com.intellij.usages.impl.rules.UsageType
import com.intellij.util.AbstractQuery
import com.intellij.util.Processor
import com.intellij.util.Query
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLScalar

@ConsistentCopyVisibility
internal data class AgentSkillSymbol private constructor(
    val virtualFile: VirtualFile,
) : Symbol, RenameTarget, RenameableSymbol {
    constructor(directory: PsiDirectory) : this(directory.virtualFile)

    override val targetName: String
        get() = virtualFile.name

    override fun createPointer(): Pointer<AgentSkillSymbol> = Pointer.hardPointer(this)

    override fun presentation(): TargetPresentation {
        return TargetPresentation.builder(targetName)
            .containerText(SKILL_MD_FILE_NAME)
            .presentation()
    }

    override fun validator(): RenameValidator {
        return SkillNameRenameValidator
    }

    override val renameTarget: RenameTarget
        get() = this

}

internal class SkillNameDeclarationProvider : PsiSymbolDeclarationProvider {
    override fun getDeclarations(element: PsiElement, offsetInElement: Int): Collection<PsiSymbolDeclaration> {
        val scalar = element as? YAMLScalar ?: return emptyList()
        if (!scalar.isSkillNameScalar) return emptyList()
        if (offsetInElement != -1 && !scalar.valueTextRangeInElement().containsOffset(offsetInElement)) {
            return emptyList()
        }
        val directory = (scalar.containingFile as? YAMLFile)?.skillDirectory ?: return emptyList()
        return listOf(SkillNameDeclaration(scalar, directory))
    }
}

private class SkillNameDeclaration(
    private val scalar: YAMLScalar,
    directory: PsiDirectory,
) : PsiSymbolDeclaration {
    private val symbol = AgentSkillSymbol(directory)

    override fun getDeclaringElement(): PsiElement = scalar
    override fun getRangeInDeclaringElement(): TextRange = scalar.valueTextRangeInElement()
    override fun getSymbol(): Symbol = symbol
}


private object SkillNameRenameValidator : RenameValidator {
    override fun validate(newName: String): RenameValidationResult =
        if (newName.isValidSkillName()) RenameValidationResult.ok()
        else RenameValidationResult.invalid()
}

internal class SkillNameRenameUsageSearcher : RenameUsageSearcher {
    @RequiresBackgroundThread
    override fun collectSearchRequest(parameters: RenameUsageSearchParameters): Query<out RenameUsage>? {
        val (project, target, _) = parameters
        val symbol = target as? AgentSkillSymbol ?: return null
        val directory = PsiManager.getInstance(project).findDirectory(symbol.virtualFile) ?: return null
        return SkillNameRenameUsageQuery(directory, parameters.searchScope)
    }
}

private class SkillNameRenameUsageQuery(
    private val directory: PsiDirectory,
    private val searchScope: SearchScope,
) : AbstractQuery<RenameUsage>() {
    override fun processResults(consumer: Processor<in RenameUsage>): Boolean {
        val nameOccurrences = ReferencesSearch.search(directory, searchScope).mapping { psiReference ->
            SkillNameOccurrenceRenameUsage(psiReference)
        }
        if (!delegateProcessResults(nameOccurrences, consumer)) return false
        return consumer.process(SkillDirectoryRenameUsage(directory.virtualFile))
    }
}

internal class SkillNameOccurrenceRenameUsage(
    private val psiReference: PsiReference,
) : PsiRenameUsage, ModifiableRenameUsage {

    override val file: PsiFile get() = psiReference.element.containingFile
    override val range: TextRange get() = psiReference.absoluteRange

    override val declaration: Boolean = true
    override val usageType: UsageType? = null
    override val modelUpdater: ModifiableRenameUsage.ModelUpdater = SkillNameOccurrenceModelUpdater

    override fun createPointer(): Pointer<SkillNameOccurrenceRenameUsage> =
        Pointer.delegatingPointer(psiReference.element.createSmartPointer()) { element ->
            val scalar = element as? YAMLScalar ?: return@delegatingPointer null
            val directory = (scalar.containingFile as? YAMLFile)?.skillDirectory ?: return@delegatingPointer null
            SkillNameOccurrenceRenameUsage(SkillDirectoryNameReference(scalar, directory))
        }

    internal fun updateName(newName: String) {
        psiReference.handleElementRename(newName)
    }

}

private object SkillNameOccurrenceModelUpdater : ModifiableRenameUsage.ModelUpdater {
    override fun prepareModelUpdate(usage: ModifiableRenameUsage): ModifiableRenameUsage.ModelUpdate? {
        val occurrence = usage as? SkillNameOccurrenceRenameUsage ?: return null
        return object : ModifiableRenameUsage.ModelUpdate {
            override fun updateModel(newName: String) {
                occurrence.updateName(newName)
            }
        }
    }
}

private class SkillDirectoryRenameUsage(
    val virtualFile: VirtualFile,
) : ModifiableRenameUsage {
    override val declaration: Boolean = true
    override val usageType: UsageType? = null
    override val fileUpdater: ModifiableRenameUsage.FileUpdater = SkillNameDirectoryFileUpdater

    override fun createPointer(): Pointer<SkillDirectoryRenameUsage> =
        Pointer.hardPointer(this)
}

private object SkillNameDirectoryFileUpdater : ModifiableRenameUsage.FileUpdater {
    override fun prepareFileUpdate(
        usage: ModifiableRenameUsage,
        newName: String,
    ): Collection<FileOperation> {
        val virtualFile = (usage as? SkillDirectoryRenameUsage)?.virtualFile ?: return emptyList()
        if (!virtualFile.isValid || virtualFile.name == newName) return emptyList()
        return listOf(FileOperation.renameFile(virtualFile, newName))
    }
}

internal fun YAMLScalar.valueTextRangeInElement(): TextRange =
    ElementManipulators.getValueTextRange(this)
