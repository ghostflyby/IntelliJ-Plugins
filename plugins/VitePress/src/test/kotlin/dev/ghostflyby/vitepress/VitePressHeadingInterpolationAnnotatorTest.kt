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
import com.intellij.testFramework.fixtures.BasePlatformTestCase

internal class VitePressHeadingInterpolationAnnotatorTest : BasePlatformTestCase() {

    fun testCollectsHeadingInterpolationRange() {
        val psiFile = myFixture.configureByText("docs.md", "# before {{a*2}} after")

        assertEquals(
            listOf(TextRange(9, 16)),
            psiFile.getVitePressHeadingInterpolationRanges(),
        )
    }

    fun testIgnoresPlainHeading() {
        val psiFile = myFixture.configureByText("docs.md", "# before after")

        assertEmpty(psiFile.getVitePressHeadingInterpolationRanges())
    }
}
