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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import org.intellij.plugins.markdown.lang.MarkdownElementType
import org.intellij.plugins.markdown.lang.parser.blocks.frontmatter.FrontMatterHeaderMarkerProvider
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar

internal const val SKILL_MD_FILE_NAME = "SKILL.md"
internal const val SKILL_NAME_KEY = "name"

internal val SKILL_MD_FRONTMATTER_TYPE = MarkdownElementType.platformType(
    FrontMatterHeaderMarkerProvider.FRONT_MATTER_HEADER,
)

private val SKILL_NAME_REGEX = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?$""")

internal val PsiFile.isSkillMarkdownFile: Boolean
    get() = name == SKILL_MD_FILE_NAME && this is MarkdownFile

internal val VirtualFile.isSkillMarkdownFile: Boolean
    get() = name == SKILL_MD_FILE_NAME

internal val PsiFile.hasSkillMdFrontmatter: Boolean
    get() = firstChild.elementType == SKILL_MD_FRONTMATTER_TYPE

internal val PsiDirectory.skillMarkdownFile: PsiFile?
    get() = findFile(SKILL_MD_FILE_NAME)

internal val YAMLFile.skillMarkdownFile: PsiFile?
    get() {
        val host = InjectedLanguageManager.getInstance(project).getInjectionHost(this) ?: return null
        if (host.elementType != SKILL_MD_FRONTMATTER_TYPE) return null
        return host.containingFile.takeIf { it.name == SKILL_MD_FILE_NAME }
    }

internal val YAMLFile.skillDirectory: PsiDirectory?
    get() = skillMarkdownFile?.parent

internal val YAMLFile.topLevelMapping: YAMLMapping?
    get() = documents.firstOrNull()?.topLevelValue as? YAMLMapping

internal fun YAMLFile.topLevelKeyValue(key: String): YAMLKeyValue? = topLevelMapping?.getKeyValueByKey(key)

internal val YAMLKeyValue.isTopLevel: Boolean
    get() = parent == (containingFile as? YAMLFile)?.topLevelMapping

internal fun PsiFile.skillNameScalar(): YAMLScalar? {
    if (!hasSkillMdFrontmatter) return null
    var result: YAMLScalar? = null
    InjectedLanguageManager.getInstance(project).enumerate(firstChild) { injectedFile, _ ->
        if (result != null) return@enumerate
        val yamlFile = injectedFile as? YAMLFile ?: return@enumerate
        result = yamlFile.topLevelKeyValue(SKILL_NAME_KEY)?.value as? YAMLScalar
    }
    return result
}

internal fun String.isValidSkillName(): Boolean = isNotEmpty() && SKILL_NAME_REGEX.matches(this)

internal fun String.toSkillNameCandidate(): String = lowercase()
    .replace(Regex("[^a-z0-9-]+"), "-")
    .replace(Regex("-{2,}"), "-")
    .trim('-')

internal fun String.canBeFixedToSkillName(): Boolean {
    if (isBlank()) return false
    return toSkillNameCandidate().isValidSkillName()
}
