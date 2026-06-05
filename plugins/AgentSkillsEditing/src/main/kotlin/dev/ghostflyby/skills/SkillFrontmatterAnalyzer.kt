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

@file:Suppress("UnstableApiUsage")
package dev.ghostflyby.skills

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.psi.PsiFile
import org.intellij.plugins.markdown.lang.psi.impl.MarkdownFrontMatterHeader
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping

/** Context for a YAML file injected into a SKILL.md frontmatter. */
internal data class SkillContext(
    val yamlFile: YAMLFile,
    val physicalFile: PsiFile,
    val hostHeader: MarkdownFrontMatterHeader,
    val document: YAMLDocument?,
    val topMapping: YAMLMapping?,
    val keyValues: Map<String, YAMLKeyValue>,
)

/**
 * Resolves whether [file] is a YAML PSI file injected into a SKILL.md
 * Markdown frontmatter. Returns [SkillContext] if so, null otherwise.
 */
internal fun resolveSkillContext(file: PsiFile): SkillContext? {
    if (file !is YAMLFile) return null
    val injectionManager = InjectedLanguageManager.getInstance(file.project)
    val host = injectionManager.getInjectionHost(file) ?: return null
    if (host !is MarkdownFrontMatterHeader) return null
    val physicalFile = host.containingFile
    if (physicalFile.name != "SKILL.md") return null
    val doc = file.documents.firstOrNull()
    val mapping = doc?.topLevelValue as? YAMLMapping
    val keyValues = mapping?.keyValues?.associateBy { it.keyText } ?: emptyMap()
    return SkillContext(file, physicalFile, host, doc, mapping, keyValues)
}

internal val NAME_REGEX = Regex("""^[a-z0-9]([a-z0-9-]*[a-z0-9])?$""")
internal const val NAME_MAX_LENGTH = 64
internal const val DESCRIPTION_MAX_LENGTH = 1024
internal const val COMPATIBILITY_MAX_LENGTH = 500
