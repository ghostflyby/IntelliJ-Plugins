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

package dev.ghostflyby.vitepress.preview

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

internal class VitePressPackageJsonScriptIndexTest {

    @Test
    fun `resolves vitepress root relative to package json directory by default`() {
        val roots = extractVitePressRootPaths("vitepress dev docs", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/docs")), roots)
    }

    @Test
    fun `resolves vitepress root relative to changed working directory`() {
        val roots = extractVitePressRootPaths("cd some/dir && vitepress dev otherdir", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/some/dir/otherdir")), roots)
    }

    @Test
    fun `uses changed working directory when vitepress root arg is omitted`() {
        val roots = extractVitePressRootPaths("cd docs && vitepress dev", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/docs")), roots)
    }

    @Test
    fun `supports omitted dev subcommand in current directory`() {
        val roots = extractVitePressRootPaths("vitepress", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo")), roots)
    }

    @Test
    fun `supports omitted dev subcommand with options and root`() {
        val roots = extractVitePressRootPaths("vitepress --port 3000 docs", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/docs")), roots)
    }

    @Test
    fun `supports omitted dev subcommand with base option and root`() {
        val roots = extractVitePressRootPaths("vitepress --base /docs/ site", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/site")), roots)
    }

    @Test
    fun `supports dev options before root`() {
        val roots = extractVitePressRootPaths("vitepress dev --open /guide --port 3000 docs", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/docs")), roots)
    }

    @Test
    fun `supports preview subcommand`() {
        val roots = extractVitePressRootPaths("vitepress preview docs", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/docs")), roots)
    }

    @Test
    fun `supports preview base option before root`() {
        val roots = extractVitePressRootPaths("vitepress preview --base /docs/ site", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/site")), roots)
    }

    @Test
    fun `supports preview subcommand after cd with options`() {
        val roots = extractVitePressRootPaths("cd site && vitepress preview --port 4173", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/site")), roots)
    }

    @Test
    fun `supports serve alias for preview`() {
        val roots = extractVitePressRootPaths("vitepress serve docs", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/docs")), roots)
    }

    @Test
    fun `supports serve alias with preview options`() {
        val roots = extractVitePressRootPaths("cd site && vitepress serve --base /docs/ --port 4173", Path.of("/repo"))

        assertEquals(setOf(Path.of("/repo/site")), roots)
    }

    @Test
    fun `ignores vitepress build command`() {
        val roots = extractVitePressRootPaths("vitepress build docs", Path.of("/repo"))

        assertEquals(emptySet<Path>(), roots)
    }
}
