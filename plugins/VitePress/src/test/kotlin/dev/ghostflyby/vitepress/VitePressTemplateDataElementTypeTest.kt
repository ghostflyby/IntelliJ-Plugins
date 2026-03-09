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

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

internal class VitePressTemplateDataElementTypeTest : BasePlatformTestCase() {

    fun testWrapsTopLevelMustacheIntoSyntheticRoot() {
        assertEquals(
            "<template>{{a*2}}</template>",
            buildVitePressTemplateDataText("{{a*2}}").toString(),
        )
    }

    fun testExtractsTopLevelMustacheFromParagraphText() {
        assertEquals(
            "<template>{{a*2}}</template>",
            buildVitePressTemplateDataText("before {{a*2}} after").toString(),
        )
    }

    fun testKeepsExistingHtmlTemplateDataAndTopLevelMustacheTogether() {
        assertEquals(
            "<template>{{a*2}}</template><Comp :value=\"n\" />",
            buildVitePressTemplateDataText("{{a*2}}\n<Comp :value=\"n\" />").toString(),
        )
    }

    fun testKeepsScriptBlockTopLevelWhenTopLevelMustacheExists() {
        assertEquals(
            "<template>{{a*2}}</template><script>\nconst answer = 42\n</script>",
            buildVitePressTemplateDataText("{{a*2}}\n<script>\nconst answer = 42\n</script>").toString(),
        )
    }

    fun testKeepsTopLevelMustacheTopLevelWhenScriptBlockPrecedesIt() {
        assertEquals(
            "<script>\nconst answer = 42\n</script><template>{{a*2}}</template>",
            buildVitePressTemplateDataText("<script>\nconst answer = 42\n</script>\n{{a*2}}").toString(),
        )
    }

    fun testIgnoresMustacheInsideCodeFence() {
        assertEquals("", buildVitePressTemplateDataText("```vue\n{{a*2}}\n```").toString())
    }

    fun testExtractsMustacheInsideAtxHeading() {
        assertEquals(
            "<template>{{a*2}}</template>",
            buildVitePressTemplateDataText("# {{a*2}}").toString(),
        )
    }

    fun testExtractsMustacheInsideSetextHeading() {
        assertEquals(
            "<template>{{a*2}}</template>",
            buildVitePressTemplateDataText("{{a*2}}\n===").toString(),
        )
    }

    fun testIgnoresMustacheInsideRichHeading() {
        assertEquals("", buildVitePressTemplateDataText("# **{{a*2}}**").toString())
    }

    fun testExtractsMustacheInsideLinkText() {
        assertEquals(
            "<template>{{a*2}}</template>",
            buildVitePressTemplateDataText("[{{a*2}}](#demo)").toString(),
        )
    }

    fun testKeepsHtmlGuestRangeInsideHeading() {
        assertEquals(
            "<Comp>{{x}}</Comp>",
            buildVitePressTemplateDataText("# hello <Comp>{{x}}</Comp>").toString(),
        )
    }

    fun testKeepsHtmlGuestRangeInsideLinkText() {
        assertEquals(
            "<Comp>{{x}}</Comp>",
            buildVitePressTemplateDataText("[hello <Comp>{{x}}</Comp>](#demo)").toString(),
        )
    }

    fun testIgnoresHtmlGuestInsideRichHeading() {
        assertEquals("<Comp>{{x}}</Comp>", buildVitePressTemplateDataText("# **hello <Comp>{{x}}</Comp>**").toString())
    }

    fun testCachesLinkInterpolationHostsOnPsiFile() {
        val psiFile = myFixture.configureByText("docs.md", "[{{a*2}}](#demo)")

        assertEquals(
            listOf(
                VitePressTemplateInterpolationHost(
                    kind = VitePressTemplateHostKind.LinkText,
                    hostRange = TextRange(1, 8),
                    interpolationRanges = listOf(TextRange(1, 8)),
                    htmlGuestRanges = emptyList(),
                    guestRanges = listOf(TextRange(1, 8)),
                ),
            ),
            psiFile.getVitePressTemplateInterpolationHosts(),
        )
    }

    fun testCachesHeadingGuestRangesOnPsiFile() {
        val psiFile = myFixture.configureByText("docs.md", "# hello <Comp>{{x}}</Comp>")

        assertEquals(
            listOf(
                VitePressTemplateInterpolationHost(
                    kind = VitePressTemplateHostKind.AtxHeading,
                    hostRange = TextRange(1, 26),
                    interpolationRanges = listOf(TextRange(14, 19)),
                    htmlGuestRanges = listOf(TextRange(8, 26)),
                    guestRanges = listOf(TextRange(8, 26)),
                ),
            ),
            psiFile.getVitePressTemplateInterpolationHosts(),
        )
    }

    fun testCachesLinkGuestRangesOnPsiFile() {
        val psiFile = myFixture.configureByText("docs.md", "[hello <Comp>{{x}}</Comp>](#demo)")

        assertEquals(
            listOf(
                VitePressTemplateInterpolationHost(
                    kind = VitePressTemplateHostKind.LinkText,
                    hostRange = TextRange(1, 25),
                    interpolationRanges = listOf(TextRange(13, 18)),
                    htmlGuestRanges = listOf(TextRange(7, 25)),
                    guestRanges = listOf(TextRange(7, 25)),
                ),
            ),
            psiFile.getVitePressTemplateInterpolationHosts(),
        )
    }

    fun testIgnoresMustacheInsideInlineCode() {
        assertEquals("", buildVitePressTemplateDataText("`{{a*2}}`").toString())
    }

    fun testCachesInterpolationHostsOnPsiFile() {
        val psiFile = myFixture.configureByText("docs.md", "# {{a*2}}")

        assertEquals(
            listOf(
                VitePressTemplateInterpolationHost(
                    kind = VitePressTemplateHostKind.AtxHeading,
                    hostRange = TextRange(1, 9),
                    interpolationRanges = listOf(TextRange(2, 9)),
                    htmlGuestRanges = emptyList(),
                    guestRanges = listOf(TextRange(2, 9)),
                ),
            ),
            psiFile.getVitePressTemplateInterpolationHosts(),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.setText("plain text")
            PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(myFixture.editor.document)
            PsiDocumentManager.getInstance(project).commitDocument(myFixture.editor.document)
        }

        assertEmpty(psiFile.getVitePressTemplateInterpolationHosts())
    }
}
