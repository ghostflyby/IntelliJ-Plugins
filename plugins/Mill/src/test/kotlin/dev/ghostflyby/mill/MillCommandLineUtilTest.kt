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

package dev.ghostflyby.mill

import dev.ghostflyby.mill.command.MillCommandLineUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

internal class MillCommandLineUtilTest {
    @Test
    fun `parses mill version from verbose version output`() {
        val version = MillCommandLineUtil.parseMillVersion("Mill Build Tool version 0.12.8")

        assertEquals("0.12.8", version)
    }

    @Test
    fun `parses mill version from bare version output`() {
        val version = MillCommandLineUtil.parseMillVersion("0.11.7")

        assertEquals("0.11.7", version)
    }

    @Test
    fun `returns null when output does not include a version`() {
        val version = MillCommandLineUtil.parseMillVersion("Usage: mill [options] [targets]")

        assertNull(version)
    }

    @Test
    fun `reads declared mill version from version file`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve(MillConstants.versionFileName), "0.12.8\n")

            assertEquals("0.12.8", MillCommandLineUtil.readDeclaredMillVersion(root))
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }
}
