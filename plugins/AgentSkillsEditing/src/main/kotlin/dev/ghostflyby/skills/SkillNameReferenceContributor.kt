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

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import com.intellij.util.ProcessingContext
import org.intellij.plugins.markdown.lang.MarkdownElementType
import org.intellij.plugins.markdown.lang.parser.blocks.frontmatter.FrontMatterHeaderMarkerProvider
import org.jetbrains.yaml.YAMLElementGenerator
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

private val FM_TYPE = MarkdownElementType.platformType(FrontMatterHeaderMarkerProvider.FRONT_MATTER_HEADER)

internal class SkillNameReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        // YAMLScalar(name) → parent directory (forward)
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(YAMLScalar::class.java),
            YamlToDirProvider(),
        )
    }
}

// ── YAML name scalar → parent directory ──
//     The directory is the declaration; the YAML scalar is its reference usage.
//     Rename refactoring on either side updates the scalar through this reference.

private class YamlToDirProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(element: PsiElement, context: ProcessingContext): Array<PsiReference> {
        val scalar = element as? YAMLScalar ?: return PsiReference.EMPTY_ARRAY
        val kv = scalar.parent as? YAMLKeyValue ?: return PsiReference.EMPTY_ARRAY
        if (kv.keyText != "name") return PsiReference.EMPTY_ARRAY
        if (!isTopLevelName(kv)) return PsiReference.EMPTY_ARRAY
        val yamlFile = kv.containingFile as? YAMLFile ?: return PsiReference.EMPTY_ARRAY
        if (resolveDir(yamlFile) == null) return PsiReference.EMPTY_ARRAY
        return arrayOf(YamlToDirRef(scalar))
    }
}

internal class YamlToDirRef(
    element: YAMLScalar,
) : PsiReferenceBase<YAMLScalar>(element, false) {
    override fun resolve(): PsiElement? = resolveDir(element.containingFile as? YAMLFile ?: return null)

    override fun handleElementRename(newElementName: String): PsiElement? {
        val gen = YAMLElementGenerator.getInstance(element.containingFile.project)
        val kv = element.parentOfType<YAMLKeyValue>() ?: return null
        val newKv = gen.createYamlKeyValue(kv.keyText, newElementName)
        kv.replace(newKv)
        return newKv.value
    }
}

// ── Helpers ──

private fun resolveDir(yamlFile: YAMLFile): PsiDirectory? {
    val host = InjectedLanguageManager.getInstance(yamlFile.project).getInjectionHost(yamlFile) ?: return null
    if (host.elementType != FM_TYPE) return null
    if (host.containingFile.name != "SKILL.md") return null
    return host.containingFile.parent
}

private fun isTopLevelName(kv: YAMLKeyValue): Boolean {
    val yamlFile = kv.containingFile as? YAMLFile ?: return false
    val mapping = yamlFile.documents.firstOrNull()?.topLevelValue as? YAMLMapping ?: return false
    return kv.parent == mapping
}
