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
import dev.ghostflyby.mill.settings.MillExecutableSource
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

internal class MillCommandLineUtilTest {
    @Test
    fun `accepts minimum supported mill version`() {
        assertNull(MillCommandLineUtil.validateSupportedMillVersion("1.1.5"))
        assertNull(MillCommandLineUtil.validateSupportedMillVersion("1.1.6"))
    }

    @Test
    fun `accepts mill versions with semver build metadata`() {
        assertNull(MillCommandLineUtil.validateSupportedMillVersion("1.1.5+12"))
    }

    @Test
    fun `rejects mill versions older than minimum supported version`() {
        val validationMessage = MillCommandLineUtil.validateSupportedMillVersion("1.1.4")

        assertNotNull(validationMessage)
    }

    @Test
    fun `rejects mill versions with semver prerelease lower than minimum`() {
        val validationMessage = MillCommandLineUtil.validateSupportedMillVersion("1.1.5-RC1")

        assertNotNull(validationMessage)
    }

    @Test
    fun `resolves bare manual mill command via path even when project wrapper exists`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve(MillConstants.wrapperScriptName), "#!/bin/sh\n")

            val resolved = MillCommandLineUtil.resolveExecutable(
                projectRoot = root,
                executableSource = MillExecutableSource.MANUAL,
                configuredExecutablePath = MillConstants.defaultExecutable,
            )

            assertEquals(MillConstants.defaultExecutable, resolved)
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
