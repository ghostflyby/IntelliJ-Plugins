/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.skills

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiFile
import com.intellij.psi.util.elementType
import com.intellij.psi.util.parentOfType
import org.intellij.plugins.markdown.lang.MarkdownElementType
import org.intellij.plugins.markdown.lang.parser.blocks.frontmatter.FrontMatterHeaderMarkerProvider
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFile
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLScalar
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

internal const val SKILL_MD_FILE_NAME = "SKILL.md"
internal const val SKILL_NAME_KEY = "name"

internal val SKILL_MD_FRONTMATTER_TYPE = MarkdownElementType.platformType(
    FrontMatterHeaderMarkerProvider.FRONT_MATTER_HEADER,
)

private val SKILL_NAME_REGEX = Regex("""^[a-z0-9]+(-[a-z0-9]+)*$""")

internal val PsiFile.isSkillMarkdownFile: Boolean
    get() = name == SKILL_MD_FILE_NAME && this is MarkdownFile

internal val VirtualFile.isSkillMarkdownFile: Boolean
    get() = name == SKILL_MD_FILE_NAME

@OptIn(ExperimentalContracts::class)
internal val PsiFile.hasSkillMdFrontmatter: Boolean
    get() {
        contract { returns(true) implies (this@hasSkillMdFrontmatter is MarkdownFile) }
        return firstChild.elementType == SKILL_MD_FRONTMATTER_TYPE
    }

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

internal val YAMLScalar.isSkillNameScalar: Boolean
    get() {
        val kv = parent as? YAMLKeyValue ?: return false
        if (kv.value != this) return false
        if (kv.keyText != SKILL_NAME_KEY || !kv.isTopLevel) return false
        val yamlFile = containingFile as? YAMLFile ?: return false
        return yamlFile.skillDirectory != null
    }

internal fun PsiFile.skillNameScalarAt(offset: Int): YAMLScalar? {
    val injectionManager = InjectedLanguageManager.getInstance(project)
    val injectedScalar = injectionManager.findInjectedElementAt(this, offset)
        ?.parentOfType<YAMLScalar>(withSelf = true)
    if (injectedScalar?.isSkillNameScalar == true) return injectedScalar

    val scalar = skillNameScalar() ?: return null
    val range = injectionManager.injectedToHost(scalar, scalar.textRange)
    return if (range.contains(offset)) scalar
    else null
}

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

internal fun String.normalizeSkillNameOrNull(): String? {
    val candidate = toSkillNameCandidate()
    return candidate.takeIf { it.isValidSkillName() }
}

internal enum class NameQuality { VALID, NORMALIZABLE, INVALID }

internal data class NamePart(
    val value: String,
    val quality: NameQuality,
    val normalized: String?,
) {
    val candidate: String? get() = when (quality) {
        NameQuality.VALID -> value
        NameQuality.NORMALIZABLE -> normalized
        NameQuality.INVALID -> null
    }
}

internal fun analyzeSkillName(value: String): NamePart {
    if (value.isValidSkillName()) {
        return NamePart(value, NameQuality.VALID, value)
    }
    val normalized = value.normalizeSkillNameOrNull()
    return if (normalized != null) {
        NamePart(value, NameQuality.NORMALIZABLE, normalized)
    } else {
        NamePart(value, NameQuality.INVALID, null)
    }
}
