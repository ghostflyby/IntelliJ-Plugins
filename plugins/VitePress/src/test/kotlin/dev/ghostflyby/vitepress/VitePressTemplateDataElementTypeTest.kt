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

import com.intellij.testFramework.fixtures.BasePlatformTestCase

internal class VitePressTemplateDataElementTypeTest : BasePlatformTestCase() {

    fun testWrapsTopLevelMustacheIntoSyntheticRoot() {
        assertEquals(
            "<vitepress-template-root>{{a*2}}</vitepress-template-root>",
            buildVitePressTemplateDataText("{{a*2}}").toString(),
        )
    }

    fun testExtractsTopLevelMustacheFromParagraphText() {
        assertEquals(
            "<vitepress-template-root>{{a*2}}</vitepress-template-root>",
            buildVitePressTemplateDataText("before {{a*2}} after").toString(),
        )
    }

    fun testKeepsExistingHtmlTemplateDataAndTopLevelMustacheTogether() {
        assertEquals(
            "<vitepress-template-root>{{a*2}}<Comp :value=\"n\" /></vitepress-template-root>",
            buildVitePressTemplateDataText("{{a*2}}\n<Comp :value=\"n\" />").toString(),
        )
    }

    fun testIgnoresMustacheInsideCodeFence() {
        assertEquals("", buildVitePressTemplateDataText("```vue\n{{a*2}}\n```").toString())
    }

    fun testIgnoresMustacheInsideHeading() {
        assertEquals("", buildVitePressTemplateDataText("# {{a*2}}").toString())
    }

    fun testIgnoresMustacheInsideLinkText() {
        assertEquals("", buildVitePressTemplateDataText("[{{a*2}}](#demo)").toString())
    }

    fun testIgnoresMustacheInsideInlineCode() {
        assertEquals("", buildVitePressTemplateDataText("`{{a*2}}`").toString())
    }
}
