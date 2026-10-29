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

package dev.ghostflyby.vitepress

import com.intellij.openapi.util.TextRange
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class VitePressTemplateInterpolationHostsTest {

    @Test
    fun `collects interpolation hosts from markdown structure`() {
        checkHosts(
            "plain paragraph text",
            emptyList(),
        )
        checkHosts(
            "before {{a*2}} after",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.Paragraph, TextRange(0, 20), listOf(TextRange(7, 14)), listOf(), listOf(TextRange(7, 14))),
            ),
        )
        checkHosts(
            "para line1\npara line2 {{x}}",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.Paragraph, TextRange(0, 27), listOf(TextRange(22, 27)), listOf(), listOf(TextRange(22, 27))),
            ),
        )
        checkHosts(
            "**bold {{x}}**",
            emptyList(),
        )
        checkHosts(
            "# {{a*2}}",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.AtxHeading, TextRange(1, 9), listOf(TextRange(2, 9)), listOf(), listOf(TextRange(2, 9))),
            ),
        )
        checkHosts(
            "###### {{a*2}}",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.AtxHeading, TextRange(6, 14), listOf(TextRange(7, 14)), listOf(), listOf(TextRange(7, 14))),
            ),
        )
        checkHosts(
            "# **{{a*2}}**",
            emptyList(),
        )
        checkHosts(
            "# hello <Comp>{{x}}</Comp>",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.AtxHeading, TextRange(1, 26), listOf(TextRange(14, 19)), listOf(TextRange(8, 26)), listOf(TextRange(8, 26))),
            ),
        )
        checkHosts(
            "{{a*2}}\n===",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.SetextHeading, TextRange(0, 7), listOf(TextRange(0, 7)), listOf(), listOf(TextRange(0, 7))),
            ),
        )
        checkHosts(
            "{{a*2}}\n---",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.SetextHeading, TextRange(0, 7), listOf(TextRange(0, 7)), listOf(), listOf(TextRange(0, 7))),
            ),
        )
        checkHosts(
            "[{{a*2}}](#demo)",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.LinkText, TextRange(1, 8), listOf(TextRange(1, 8)), listOf(), listOf(TextRange(1, 8))),
            ),
        )
        checkHosts(
            "[hello <Comp>{{x}}</Comp>](#demo)",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.LinkText, TextRange(1, 25), listOf(TextRange(13, 18)), listOf(TextRange(7, 25)), listOf(TextRange(7, 25))),
            ),
        )
        checkHosts(
            "[**{{a*2}}**](#demo)",
            emptyList(),
        )
        checkHosts(
            "![{{a*2}}](img.png)",
            emptyList(),
        )
        checkHosts(
            "[outer [inner] {{x}}](#demo)",
            emptyList(),
        )
        checkHosts(
            "[{{a*2}}]",
            emptyList(),
        )
        checkHosts(
            "[{{a*2}}][ref]\n\n[ref]: /x",
            emptyList(),
        )
        checkHosts(
            "# [{{x}}](y)",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.LinkText, TextRange(3, 8), listOf(TextRange(3, 8)), listOf(), listOf(TextRange(3, 8))),
            ),
        )
        checkHosts(
            "# ![{{x}}](y)",
            emptyList(),
        )
        checkHosts(
            "[]()",
            emptyList(),
        )
        // Autolinks lex as HTML blocks under the permissive VitePress HTML rules, so they are not plain-text hosts.
        checkHosts(
            "<https://example.com/{{x}}>",
            emptyList(),
        )
        checkHosts(
            "| head |\n| --- |\n| {{a*2}} |",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.TableCell, TextRange(18, 27), listOf(TextRange(19, 26)), listOf(), listOf(TextRange(19, 26))),
            ),
        )
        checkHosts(
            "| head |\n| --- |\n| [{{a*2}}](#demo) |",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.LinkText, TextRange(20, 27), listOf(TextRange(20, 27)), listOf(), listOf(TextRange(20, 27))),
            ),
        )
        checkHosts(
            "| head |\n| --- |\n| **{{a*2}}** |",
            emptyList(),
        )
        checkHosts(
            "```vue\n{{a*2}}\n```",
            emptyList(),
        )
        checkHosts(
            "`{{a*2}}`",
            emptyList(),
        )
        checkHosts(
            "- item {{x}}",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.Paragraph, TextRange(2, 12), listOf(TextRange(7, 12)), listOf(), listOf(TextRange(7, 12))),
            ),
        )
        checkHosts(
            "> quote {{x}}",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.Paragraph, TextRange(2, 13), listOf(TextRange(8, 13)), listOf(), listOf(TextRange(8, 13))),
            ),
        )
        checkHosts(
            "text <Comp>{{x}}</Comp> more",
            listOf(
                VitePressTemplateInterpolationHost(VitePressTemplateHostKind.Paragraph, TextRange(0, 28), listOf(TextRange(11, 16)), listOf(TextRange(5, 23)), listOf(TextRange(5, 23))),
            ),
        )
    }

    private fun checkHosts(
        sourceCode: String,
        expected: List<VitePressTemplateInterpolationHost>,
    ) {
        Assertions.assertEquals(
            expected,
            collectTemplateInterpolationHosts(sourceCode),
            "Mismatch for input: " + sourceCode.replace("\n", "\\n"),
        )
    }
}
