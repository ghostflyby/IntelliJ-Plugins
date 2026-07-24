/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.vitepress

import com.intellij.lang.Language
import com.intellij.psi.templateLanguages.TemplateLanguage
import org.intellij.plugins.markdown.lang.MarkdownLanguage
import java.io.Serial

public object VitePressLanguage : Language(MarkdownLanguage.INSTANCE, "VitePress"), TemplateLanguage {
    @Serial
    private fun readResolve(): Any = VitePressLanguage
}

