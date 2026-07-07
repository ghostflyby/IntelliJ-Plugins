/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.skills

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import org.jetbrains.yaml.psi.YAMLFile

internal class SkillNameGotoDeclarationHandler : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor,
    ): Array<PsiElement>? {
        val sourceFile = sourceElement?.containingFile ?: return null
        val hostFile = InjectedLanguageManager.getInstance(sourceFile.project).getTopLevelFile(sourceFile)
        val scalar = hostFile.skillNameScalarAt(offset) ?: return null

        val directory = (scalar.containingFile as? YAMLFile)?.skillDirectory ?: return null
        return arrayOf(directory)
    }
}
