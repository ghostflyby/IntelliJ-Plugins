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

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.roots.DependencyScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
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
    fun `builds content root from explicit mill metadata roots`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val sourceRoot = root.resolve("generated/src").createDirectories()
            val resourceRoot = root.resolve("generated/resources").createDirectories()

            val contentRoot = MillProjectResolverSupport.buildContentRoot(
                root,
                listOf(
                    ExternalSystemSourceType.SOURCE_GENERATED to sourceRoot,
                    ExternalSystemSourceType.RESOURCE_GENERATED to resourceRoot,
                ),
            )

            assertEquals(
                listOf(sourceRoot.toString()),
                contentRoot.getPaths(ExternalSystemSourceType.SOURCE_GENERATED).map { it.path },
            )
            assertEquals(
                listOf(resourceRoot.toString()),
                contentRoot.getPaths(ExternalSystemSourceType.RESOURCE_GENERATED).map { it.path },
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

    @Test
    fun `parses generic string lists from mill show output`() {
        val values = MillShowTargetPathResolver.parseStringList(
            """
            [info] resolving
            ["foo","ref:/tmp/coursier/cache/a.jar","qux[2.13]"]
            """.trimIndent(),
        )

        assertEquals(listOf("foo", "/tmp/coursier/cache/a.jar", "qux[2.13]"), values)
    }

    @Test
    fun `parses hashed path refs from mill show output`() {
        val values = MillShowTargetPathResolver.parseStringList(
            """
            ["ref:v0:8befb7a8:/tmp/coursier/cache/a.jar","qref:v1:feedbeef:C:\\Users\\me\\cache\\b.jar"]
            """.trimIndent(),
        )

        assertEquals(
            listOf("/tmp/coursier/cache/a.jar", """C:\Users\me\cache\b.jar"""),
            values,
        )
    }

    @Test
    fun `parses single string mill show output`() {
        val value = MillShowTargetPathResolver.parseSingleStringValue(
            """
            [info] resolving
            "2.13.16"
            """.trimIndent(),
        )

        assertEquals("2.13.16", value)
    }

    @Test
    fun `parses resolved mill targets`() {
        val targets = MillModuleDiscovery.parseResolvedTargets(
            """
            [info] compiling
            foo.compile
            foo.test.compile
            bar.runBackground
            """.trimIndent(),
        )

        assertEquals(listOf("foo.compile", "foo.test.compile", "bar.runBackground"), targets)
    }

    @Test
    fun `discovers modules from resolved targets and filesystem layout`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.sc"), "// mill")
            root.resolve("foo/src").createDirectories()
            root.resolve("foo/test/src").createDirectories()
            root.resolve("bar/src").createDirectories()

            val modules = MillModuleDiscovery.discoverModulesFromTargets(
                root = root,
                projectName = "sample",
                resolvedTargets = listOf("foo.compile", "foo.test.compile", "bar.compile"),
            )

            assertEquals(listOf("bar", "foo", "foo.test"), modules.map { it.displayName })
            assertEquals(listOf(root, root, root), modules.map { it.projectRoot })
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `discovers modules from resolved targets without requiring filesystem layout`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.sc"), "// mill")

            val modules = MillModuleDiscovery.discoverModulesFromTargets(
                root = root,
                projectName = "sample",
                resolvedTargets = listOf("foo.compile", "foo.test.test", "bar.compile"),
            )

            assertEquals(listOf("bar", "foo", "foo.test"), modules.map { it.displayName })
            assertEquals(
                listOf(root.resolve("bar"), root.resolve("foo"), root.resolve("foo/test")),
                modules.map { it.directory },
            )
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `filters mill pseudo modules from resolved targets`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.mill"), "// mill")

            val modules = MillModuleDiscovery.discoverModulesFromTargets(
                root = root,
                projectName = "sample",
                resolvedTargets = listOf(
                    "examples.compile",
                    "examples.sources",
                    "selective.resolve",
                    "selective.run",
                ),
            )

            assertEquals(listOf("examples"), modules.map { it.displayName })
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `builds module dependency data for mill modules`() {
        val owner = MillProjectResolverSupport.buildModuleData(Path.of("/tmp/project"), Path.of("/tmp/project/foo/test"), "foo.test")
        val target = MillProjectResolverSupport.buildModuleData(Path.of("/tmp/project"), Path.of("/tmp/project/foo"), "foo")

        val dependency = MillProjectResolverSupport.buildModuleDependency(owner, target)

        assertEquals(DependencyScope.COMPILE, dependency.scope)
        assertEquals(target, dependency.target)
        assertEquals(owner, dependency.ownerModule)
    }

    @Test
    fun `normalizes mill module dependency prefixes`() {
        assertEquals("foo", MillModuleDependencyResolver.normalizeDependencyPrefix("foo"))
        assertEquals("foo.bar", MillModuleDependencyResolver.normalizeDependencyPrefix("_.foo.bar"))
        assertEquals("foo.bar", MillModuleDependencyResolver.normalizeDependencyPrefix("foo/bar"))
        assertEquals("foo.cross", MillModuleDependencyResolver.normalizeDependencyPrefix("foo.cross[2.13.16]"))
    }

    @Test
    fun `creates per-module task data from resolved targets`() {
        val root = Path.of("/tmp/project")
        val tasks = MillProjectResolverSupport.createTaskData(
            root = root,
            discoveredModules = listOf(
                MillDiscoveredModule("foo", "foo", root, root.resolve("foo")),
                MillDiscoveredModule("foo.test", "foo.test", root, root.resolve("foo/test"), productionModulePrefix = "foo"),
            ),
            resolvedTargets = listOf("foo.compile", "foo.test.test", "foo.runBackground", "foo.internalTarget"),
        )

        val taskNames = tasks.map { it.name }
        assertTrue(taskNames.contains("__.compile"))
        assertTrue(taskNames.contains("foo.compile"))
        assertTrue(taskNames.contains("foo.test.test"))
        assertTrue(taskNames.contains("foo.runBackground"))
        assertTrue(taskNames.contains("show foo.compileClasspath"))
        assertTrue(taskNames.contains("show foo.test.compileClasspath"))
        assertTrue(!taskNames.contains("foo.internalTarget"))
    }

    @Test
    fun `builds mill command lines with jvm options before tasks`() {
        val command = MillCommandLineUtil.buildMillCommand(
            projectRoot = Path.of("/tmp/project"),
            executable = "mill",
            jvmOptionsText = """-J-Xmx2g "-J-Dmill.profile=dev mode"""",
            arguments = listOf("show", "foo.compileClasspath"),
        )

        assertEquals(
            listOf("mill", "-J-Xmx2g", "-J-Dmill.profile=dev mode", "show", "foo.compileClasspath"),
            command,
        )
    }

    @Test
    fun `prefers project mill wrapper over global command`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val wrapper = Files.writeString(root.resolve("mill"), "#!/bin/sh\n")

            val command = MillCommandLineUtil.buildMillCommand(
                projectRoot = root,
                executable = MillConstants.defaultExecutable,
                jvmOptionsText = "",
                arguments = listOf("resolve", MillConstants.moduleDiscoveryQuery),
            )

            assertEquals(wrapper.toString(), command.first())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `creates default resolve task for module discovery query`() {
        val tasks = MillProjectResolverSupport.createTaskData(Path.of("/tmp/project"))

        assertTrue(tasks.any { it.name == "resolve ${MillConstants.moduleDiscoveryQuery}" })
        assertTrue(tasks.none { it.name == "resolve _" })
    }

    @Test
    fun `resolves configured relative executable against project root`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val wrapper = root.resolve("tools/mill")
            Files.createDirectories(wrapper.parent)
            Files.writeString(wrapper, "#!/bin/sh\n")

            val executable = MillCommandLineUtil.resolveExecutable(root, "tools/mill")

            assertEquals(wrapper.toString(), executable)
        } finally {
            deleteRecursively(root)
        }
    }

    private fun deleteRecursively(root: java.nio.file.Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }
}
