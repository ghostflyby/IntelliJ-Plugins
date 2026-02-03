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

package dev.ghostflyby.vitepress.markdown

import org.intellij.lang.annotations.Language

/**
 * Shared permissive HTML tag patterns used by both the lexer and block provider to keep
 * Vue/SFC-friendly behavior in sync.
 */
internal object VitePressHtmlPatterns {
    internal const val IMMEDIATE_TAG_GROUP_INDEX = 5

    /**
     * Single-line tag start without closing `>` OR self-closing `/>`; ends immediately.
     */
    @Suppress("RegExpUnnecessaryNonCapturingGroup")
    @Language("RegExp")
    private const val IMMEDIATE_TAG = "(?:</?[^\\s>/][^\\n>]*$|</?[^\\s>/][^\\n>]*?/\\s*>\\s*$)"

    /**
     * Open tag with a required `>` (not self-closing) that should continue until a closing tag.
     */
    @Language("RegExp")
    private const val OPEN_TAG_BLOCK = "</?[^\\s>/][^\\n>]*[^/]>.*"

    internal val OPEN_CLOSE_REGEXES: List<Pair<Regex, Regex?>> = listOf(
        // 0
        Regex("<(?:script|pre|style)(?: |>|$)", RegexOption.IGNORE_CASE) to
                Regex("</(?:script|style|pre)>", RegexOption.IGNORE_CASE),

        // 1
        Regex("<!--") to Regex("-->"),

        // 2
        Regex("<\\?") to Regex("\\?>"),

        // 3
        Regex("<![A-Z]") to Regex(">"),

        // 4
        Regex("<!\\[CDATA\\[") to Regex("]]>"),

        // 5 (immediate tag)
        Regex(IMMEDIATE_TAG, RegexOption.IGNORE_CASE) to Regex("$"),

        // 6 (open tag -> end at next closing tag)
        Regex(OPEN_TAG_BLOCK, RegexOption.IGNORE_CASE) to Regex("</[^>]+>", RegexOption.IGNORE_CASE),
    )

    internal val FIND_START_REGEX = Regex(
        "^(${OPEN_CLOSE_REGEXES.joinToString(separator = "|") { "(${it.first.pattern})" }})",
    )
}
