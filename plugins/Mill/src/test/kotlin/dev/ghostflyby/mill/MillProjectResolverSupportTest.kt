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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import kotlin.io.path.createDirectories

internal class MillProjectResolverSupportTest {
    @Test
    fun `recognizes supported mill project files`() {
        assertTrue(MillProjectResolverSupport.isProjectFileName("build.sc"))
        assertTrue(MillProjectResolverSupport.isProjectFileName("mill.sc"))
        assertTrue(MillProjectResolverSupport.isProjectFileName("build.mill"))
    }

    @Test
    fun `finds root from build file path`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.sc"), "// mill")

            val resolved = MillProjectResolverSupport.findProjectRoot(root.resolve("build.sc").toString())

            assertEquals(root, resolved)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `collects existing mill config files`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.sc"), "// mill")
            Files.writeString(root.resolve(".mill-version"), "0.12.8")

            val files = MillProjectResolverSupport.findAffectedExternalProjectFiles(root.toString())

            assertEquals(listOf(".mill-version", "build.sc"), files.map { it.name }.sorted())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `adds mill style source roots and excludes`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.sc"), "// mill")
            root.resolve("src").createDirectories()
            root.resolve("test/src").createDirectories()
            root.resolve(".bsp").createDirectories()

            val contentRoot = MillProjectResolverSupport.buildContentRoot(root)

            assertTrue(
                contentRoot.getPaths(com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType.SOURCE)
                    .isNotEmpty(),
            )
            assertTrue(
                contentRoot.getPaths(com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType.TEST)
                    .isNotEmpty(),
            )
            assertTrue(
                contentRoot.getPaths(com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType.EXCLUDED)
                    .isNotEmpty(),
            )
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `parses compile classpath from mill show output`() {
        val paths = MillClasspathResolver.parsePathList(
            """
            [info] compiling 1 Scala source to out/foo/compile.dest/classes ...
            ["/tmp/coursier/cache/a.jar","/tmp/coursier/cache/b.jar"]
            """.trimIndent(),
        )

        assertEquals(listOf("/tmp/coursier/cache/a.jar", "/tmp/coursier/cache/b.jar"), paths)
    }

    @Test
    fun `parses escaped paths from mill show output`() {
        val paths = MillClasspathResolver.parsePathList("""["C:\\\\Users\\\\me\\\\cache\\\\lib.jar"]""")

        assertEquals(listOf("""C:\\Users\\me\\cache\\lib.jar"""), paths)
    }

    private fun deleteRecursively(root: java.nio.file.Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }
}
