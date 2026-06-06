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

import com.intellij.codeInsight.intention.PriorityAction
import com.intellij.codeInspection.*
import com.intellij.lang.injection.InjectedLanguageManager.getInstance
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.SmartPointerManager
import com.intellij.psi.SmartPsiElementPointer
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLValue

private data class SkillNameState(
    val name: NamePart,
    val dir: NamePart,
) {
    val same: Boolean get() = name.value == dir.value
}

private enum class ProblemKind { INVALID_NAME, INVALID_DIRECTORY, MISMATCH }

private sealed interface NameFixDecision
private data class AutoSetName(val value: String) : NameFixDecision
private data class AutoRenameDir(val value: String) : NameFixDecision
private data class AutoRenameBoth(val value: String) : NameFixDecision
private data class ManualRename(val initialName: String?) : NameFixDecision

private enum class FixPriority {
    EXACT_SYNC, NORMALIZED_SYNC, COMMON_NORMALIZE, LOCAL_NORMALIZE, MANUAL_RENAME,
}

private data class CandidateFix(
    val priority: FixPriority,
    val decision: NameFixDecision,
)

private fun collectFixes(state: SkillNameState): List<CandidateFix> {
    val name = state.name
    val dir = state.dir
    val fixes = mutableListOf<CandidateFix>()

    if (!state.same) {
        if (dir.quality == NameQuality.VALID) {
            fixes += CandidateFix(FixPriority.EXACT_SYNC, AutoSetName(dir.value))
        }
        if (name.quality == NameQuality.VALID) {
            fixes += CandidateFix(FixPriority.EXACT_SYNC, AutoRenameDir(name.value))
        }
    }

    if (name.normalized != null && dir.normalized != null && name.normalized == dir.normalized) {
        fixes += CandidateFix(FixPriority.COMMON_NORMALIZE, AutoRenameBoth(name.normalized))
    }

    if (!state.same) {
        dir.normalized?.let { fixes += CandidateFix(FixPriority.NORMALIZED_SYNC, AutoSetName(it)) }
        name.normalized?.let { fixes += CandidateFix(FixPriority.NORMALIZED_SYNC, AutoRenameDir(it)) }
    }

    if (name.quality == NameQuality.NORMALIZABLE) {
        fixes += CandidateFix(FixPriority.LOCAL_NORMALIZE, AutoSetName(name.normalized!!))
    }
    if (dir.quality == NameQuality.NORMALIZABLE) {
        fixes += CandidateFix(FixPriority.LOCAL_NORMALIZE, AutoRenameDir(dir.normalized!!))
    }

    fixes += CandidateFix(FixPriority.MANUAL_RENAME, ManualRename(name.candidate))

    return fixes
}

private fun fixesForProblem(state: SkillNameState, problem: ProblemKind): List<NameFixDecision> {
    val fixes = collectFixes(state)
    val filtered = fixes.filter {
        when (problem) {
            ProblemKind.INVALID_NAME ->
                it.decision is AutoSetName || it.decision is AutoRenameBoth || it.decision is ManualRename
            ProblemKind.INVALID_DIRECTORY ->
                it.decision is AutoRenameDir || it.decision is AutoRenameBoth || it.decision is ManualRename
            ProblemKind.MISMATCH ->
                it.priority <= FixPriority.NORMALIZED_SYNC ||
                it.decision is AutoRenameBoth ||
                it.decision is ManualRename
        }
    }
    return suppressRedundantFixes(state, filtered).map { it.decision }
}

private fun suppressRedundantFixes(
    state: SkillNameState,
    candidates: List<CandidateFix>,
): List<CandidateFix> {
    val hasBoth = candidates.any { it.decision is AutoRenameBoth }
    val bothValues = candidates.mapNotNull { (it.decision as? AutoRenameBoth)?.value }.toSet()
    return candidates.filter { c ->
        when (val d = c.decision) {
            is AutoSetName -> d.value != state.name.value && !(hasBoth && d.value in bothValues)
            is AutoRenameDir -> d.value != state.dir.value && !(hasBoth && d.value in bothValues)
            is AutoRenameBoth -> d.value != state.name.value || d.value != state.dir.value
            is ManualRename -> true
        }
    }
}

private fun NameFixDecision.toQuickFix(kv: YAMLKeyValue): LocalQuickFix = when (this) {
    is AutoSetName -> AutoSetNameQuickFix(value)
    is AutoRenameDir -> AutoRenameDirQuickFix(value)
    is AutoRenameBoth -> AutoRenameBothQuickFix(value)
    is ManualRename -> ManualRenameQuickFix(kv.value?.let { SmartPointerManager.createPointer(it) })
}

private class AutoSetNameQuickFix(private val value: String) : LocalQuickFix, PriorityAction {
    override fun getPriority() = PriorityAction.Priority.TOP
    override fun getName() = SkillMdBundle.message("quickfix.auto.set.name", value)
    override fun getFamilyName() = getName()
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val kv = descriptor.psiElement as? YAMLKeyValue ?: return
        kv.replace(YAMLElementGenerator.getInstance(project).createYamlKeyValue(kv.keyText, value))
    }
}

private class AutoRenameDirQuickFix(private val value: String) : LocalQuickFix, PriorityAction {
    override fun getPriority() = PriorityAction.Priority.HIGH
    override fun getName() = SkillMdBundle.message("quickfix.auto.rename.dir", value)
    override fun getFamilyName() = getName()
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val kv = descriptor.psiElement as? YAMLKeyValue ?: return
        val yamlFile = kv.containingFile as? YAMLFile ?: return
        yamlFile.skillDirectory?.virtualFile?.rename(this, value)
    }
}

private class AutoRenameBothQuickFix(private val value: String) : LocalQuickFix, PriorityAction {
    override fun getPriority() = PriorityAction.Priority.NORMAL
    override fun getName() = SkillMdBundle.message("quickfix.auto.rename.both", value)
    override fun getFamilyName() = getName()
    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val kv = descriptor.psiElement as? YAMLKeyValue ?: return
        val yamlFile = kv.containingFile as? YAMLFile ?: return
        val dir = yamlFile.skillDirectory ?: return
        kv.replace(YAMLElementGenerator.getInstance(project).createYamlKeyValue(kv.keyText, value))
        if (dir.name != value) {
            dir.virtualFile.rename(this, value)
        }
    }
}

private class ManualRenameQuickFix(
    private val scalarPtr: SmartPsiElementPointer<YAMLValue>?,
) : LocalQuickFix, PriorityAction {
    override fun getPriority() = PriorityAction.Priority.LOW
    override fun getName() = SkillMdBundle.message("quickfix.manual.rename.skill")
    override fun getFamilyName() = getName()
    override fun startInWriteAction() = false

    override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
        val scalar = scalarPtr?.element as? YAMLScalar ?: return
        val hostVFile = getInstance(project)
            .getTopLevelFile(scalar.containingFile)?.virtualFile ?: return
        val editor = FileEditorManager.getInstance(project)
            .openTextEditor(OpenFileDescriptor(project, hostVFile), true) ?: return
        performSkillNameInlineRename(scalar, editor, project)
    }
}

internal class SkillNameInspection : LocalInspectionTool() {

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                if (file !is YAMLFile) return
                val physicalFile = file.skillMarkdownFile ?: return
                val kv = file.topLevelKeyValue(SKILL_NAME_KEY) ?: return
                val dirName = physicalFile.parent?.name ?: return
                val state = SkillNameState(
                    name = analyzeSkillName(kv.valueText),
                    dir = analyzeSkillName(dirName),
                )

                if (state.name.quality != NameQuality.VALID) {
                    holder.registerProblem(
                        kv, SkillMdBundle.message("inspection.skill.name.invalid", state.name.value),
                        ProblemHighlightType.WARNING, null,
                        *fixesForProblem(state, ProblemKind.INVALID_NAME)
                            .map { it.toQuickFix(kv) }
                            .toTypedArray(),
                    )
                }
                if (state.dir.quality != NameQuality.VALID) {
                    holder.registerProblem(
                        kv, SkillMdBundle.message("inspection.skill.directory.invalid", state.dir.value),
                        ProblemHighlightType.WARNING, null,
                        *fixesForProblem(state, ProblemKind.INVALID_DIRECTORY)
                            .map { it.toQuickFix(kv) }
                            .toTypedArray(),
                    )
                }
                if (!state.same) {
                    holder.registerProblem(
                        kv, SkillMdBundle.message("inspection.skill.mismatch", state.name.value, state.dir.value),
                        ProblemHighlightType.WARNING, null,
                        *fixesForProblem(state, ProblemKind.MISMATCH)
                            .map { it.toQuickFix(kv) }
                            .toTypedArray(),
                    )
                }
            }
        }
    }
}
