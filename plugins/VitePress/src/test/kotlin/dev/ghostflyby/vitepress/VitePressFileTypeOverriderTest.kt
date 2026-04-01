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
import org.intellij.plugins.markdown.lang.MarkdownFileType

internal class VitePressFileTypeOverriderTest : BasePlatformTestCase() {

    fun `test vitepress roots always use vitepress file type`() {
        assertSame(
            VitePressFiletype,
            overriddenMarkdownFileType(
                isUnderVitePressRoot = true,
                isVueLanguageServiceWorkaroundEnabled = false,
            ),
        )
        assertSame(
            VitePressFiletype,
            overriddenMarkdownFileType(
                isUnderVitePressRoot = true,
                isVueLanguageServiceWorkaroundEnabled = true,
            ),
        )
    }

    fun `test plain markdown stays default when workaround disabled`() {
        assertNull(
            overriddenMarkdownFileType(
                isUnderVitePressRoot = false,
                isVueLanguageServiceWorkaroundEnabled = false,
            ),
        )
    }

    fun `test plain markdown is forced back to markdown when workaround enabled`() {
        assertSame(
            MarkdownFileType.INSTANCE,
            overriddenMarkdownFileType(
                isUnderVitePressRoot = false,
                isVueLanguageServiceWorkaroundEnabled = true,
            ),
        )
    }
}
