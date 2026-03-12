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

import com.intellij.openapi.externalSystem.model.DataNode
import com.intellij.openapi.externalSystem.model.ProjectKeys
import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.roots.DependencyScope
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.project.*
import dev.ghostflyby.mill.script.MillBuildScriptSupport
import dev.ghostflyby.mill.sdk.MillModuleJdkSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories

internal class MillProjectResolverSupportTest {
    @Test
    fun `recognizes supported mill project files`() {
        assertTrue(MillProjectResolverSupport.isProjectFileName("build.mill"))
        assertTrue(MillProjectResolverSupport.isProjectFileName("build.mill.yaml"))
        assertTrue(MillBuildScriptSupport.isBuildScriptFileName("build.mill"))
    }

    @Test
    fun `finds root from build file path`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.mill"), "// mill")

            val resolved = MillProjectResolverSupport.findProjectRoot(root.resolve("build.mill").toString())

            assertEquals(root, resolved)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `finds root from yaml build file path`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.mill.yaml"), "modules: []")

            val resolved = MillProjectResolverSupport.findProjectRoot(root.resolve("build.mill.yaml").toString())

            assertEquals(root, resolved)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `normalizes module jdk home path`() {
        val normalized = MillModuleJdkSupport.normalizeJdkHomePath(" /tmp/../tmp/jdk ")

        assertEquals("/tmp/jdk", normalized)
    }

    @Test
    fun `parses java home from jvm show settings output`() {
        val javaHome = MillCommandLineUtil.parseJavaHome(
            """
            VM settings:
                Max. Heap Size (Estimated): 29.97G
            Property settings:
                file.encoding = UTF-8
                java.home = /Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
                java.version = 21.0.6
            """.trimIndent(),
        )

        assertEquals("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home", javaHome)
    }

    @Test
    fun `creates unique module jdk names`() {
        val uniqueName = MillModuleJdkSupport.createUniqueSdkName(
            baseName = "temurin-21",
            existingNames = linkedSetOf("temurin-21", "temurin-21 (2)"),
        )

        assertEquals("temurin-21 (3)", uniqueName)
    }

    @Test
    fun `omits sentinel prefix for default module query targets`() {
        val rootModule = MillDiscoveredModule(
            "sample",
            MillConstants.rootModulePrefix,
            Path.of("/tmp/project"),
            Path.of("/tmp/project"),
        )
        val namedModule = MillDiscoveredModule("foo", "foo", Path.of("/tmp/project"), Path.of("/tmp/project/foo"))

        assertEquals("compileClasspath", rootModule.queryTarget("compileClasspath"))
        assertEquals("java", rootModule.queryTarget("java"))
        assertEquals("foo.compileClasspath", namedModule.queryTarget("compileClasspath"))
    }

    @Test
    fun `detects mill config in project root`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.mill"), "// mill")

            assertTrue(MillProjectResolverSupport.hasMillConfig(root.toString()))
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `finds linked project path for file context`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val nestedFile = root.resolve("foo/src/Main.scala")
            nestedFile.parent.createDirectories()
            Files.writeString(nestedFile, "object Main")

            val linkedProjectPath = MillProjectResolverSupport.findLinkedProjectPathForContext(
                nestedFile.toString(),
                listOf(root.toString()),
            )

            assertEquals(root.toString(), linkedProjectPath)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `prefers deepest linked project path for nested contexts`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val nestedRoot = root.resolve("nested").createDirectories()
            val nestedFile = nestedRoot.resolve("src/Main.scala")
            nestedFile.parent.createDirectories()
            Files.writeString(nestedFile, "object Main")

            val linkedProjectPath = MillProjectResolverSupport.findLinkedProjectPathForContext(
                nestedFile.toString(),
                listOf(root.toString(), nestedRoot.toString()),
            )

            assertEquals(nestedRoot.toString(), linkedProjectPath)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `collects existing mill config files`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.mill"), "// mill")
            Files.writeString(root.resolve(".mill-version"), "0.12.8")

            val files = MillProjectResolverSupport.findAffectedExternalProjectFiles(root.toString())

            assertEquals(listOf(".mill-version", "build.mill"), files.map { it.name }.sorted())
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `adds mill style source roots and excludes`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            Files.writeString(root.resolve("build.mill"), "// mill")
            root.resolve("src").createDirectories()
            root.resolve("test/src").createDirectories()
            root.resolve(".bsp").createDirectories()

            val contentRoot = MillProjectResolverSupport.buildContentRoot(root)

            assertTrue(
                contentRoot.getPaths(ExternalSystemSourceType.SOURCE)
                    .isNotEmpty(),
            )
            assertTrue(
                contentRoot.getPaths(ExternalSystemSourceType.TEST)
                    .isNotEmpty(),
            )
            assertTrue(
                contentRoot.getPaths(ExternalSystemSourceType.EXCLUDED)
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
    fun `build script content root keeps mill output available for generated sources`() {
        val root = Files.createTempDirectory("mill-project")
        try {
            val generatedSourceRoot = root.resolve("out/mill-build/generatedScriptSources.dest/support").createDirectories()
            root.resolve("out").createDirectories()
            root.resolve(".bsp").createDirectories()

            val contentRoot = MillProjectResolverSupport.buildBuildScriptContentRoot(root, listOf(generatedSourceRoot))

            assertEquals(
                listOf(generatedSourceRoot.toString()),
                contentRoot.getPaths(ExternalSystemSourceType.SOURCE_GENERATED).map { it.path },
            )
            assertEquals(
                listOf(root.resolve(".bsp").toString()),
                contentRoot.getPaths(ExternalSystemSourceType.EXCLUDED).map { it.path },
            )
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `parses compile classpath from mill show output`() {
        val paths = MillCommandLineUtil.parsePathList(
            """["/tmp/coursier/cache/a.jar","/tmp/coursier/cache/b.jar"]""",
        )

        assertEquals(listOf("/tmp/coursier/cache/a.jar", "/tmp/coursier/cache/b.jar"), paths)
    }

    @Test
    fun `parses generated mill script source roots`() {
        val roots = MillBuildScriptSupport.parseGeneratedSourceRootPaths(
            """
            {
              "value": {
                "wrapped": ["ref:v0:1111:/tmp/project/out/mill-build/generatedScriptSources.dest/wrapped"],
                "support": ["qref:v1:2222:/tmp/project/out/mill-build/generatedScriptSources.dest/support"]
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                Path.of("/tmp/project/out/mill-build/generatedScriptSources.dest/support"),
                Path.of("/tmp/project/out/mill-build/generatedScriptSources.dest/wrapped"),
            ),
            roots,
        )
    }

    @Test
    fun `parses escaped paths from mill show output`() {
        val paths = MillCommandLineUtil.parsePathList("""["C:\\\\Users\\\\me\\\\cache\\\\lib.jar"]""")

        assertEquals(listOf("""C:\\Users\\me\\cache\\lib.jar"""), paths)
    }

    @Test
    fun `parses generic string lists from mill show output`() {
        val values = MillCommandLineUtil.parseStringList(
            """["foo","ref:/tmp/coursier/cache/a.jar","qux[2.13]"]""",
        )

        assertEquals(listOf("foo", "/tmp/coursier/cache/a.jar", "qux[2.13]"), values)
    }

    @Test
    fun `parses hashed path refs from mill show output`() {
        val values = MillCommandLineUtil.parseStringList(
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
        val value = MillCommandLineUtil.parseSingleStringValue(""""2.13.16"""")

        assertEquals("2.13.16", value)
    }

    @Test
    fun `loads mill build script model from mill output files`() {
        val root = Files.createTempDirectory("mill-script")
        try {
            val outputRoot = root.resolve(MillConstants.buildScriptOutputDirectory).createDirectories()
            val supportRoot = outputRoot.resolve("generatedScriptSources.dest/support").createDirectories()
            val wrappedRoot = outputRoot.resolve("generatedScriptSources.dest/wrapped").createDirectories()
            val classesRoot = outputRoot.resolve("compile-resources").createDirectories()
            val jarPath = Files.write(root.resolve("mill-libs_3-1.1.3.jar"), byteArrayOf())

            Files.writeString(
                outputRoot.resolve(MillConstants.buildScriptGeneratedSourcesFileName),
                """
                {
                  "value": {
                    "wrapped": ["ref:v0:1111:${wrappedRoot.toAbsolutePath()}"],
                    "support": ["qref:v1:2222:${supportRoot.toAbsolutePath()}"]
                  }
                }
                """.trimIndent(),
            )
            Files.writeString(
                outputRoot.resolve(MillConstants.buildScriptClasspathFileName),
                """
                {
                  "value": [
                    "qref:v1:3333:${jarPath.toAbsolutePath()}",
                    "ref:v0:4444:${classesRoot.toAbsolutePath()}"
                  ]
                }
                """.trimIndent(),
            )
            Files.writeString(
                outputRoot.resolve("scalaVersion.json"),
                """
                {
                  "value": "3.8.2"
                }
                """.trimIndent(),
            )
            Files.writeString(
                outputRoot.resolve("scalaCompilerClasspath.json"),
                """
                {
                  "value": [
                    "qref:v1:5555:${jarPath.toAbsolutePath()}"
                  ]
                }
                """.trimIndent(),
            )
            Files.writeString(
                outputRoot.resolve("javaHome.json"),
                """
                {
                  "value": "/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home"
                }
                """.trimIndent(),
            )

            val model = MillBuildScriptSupport.loadModel(root)

            requireNotNull(model)
            assertEquals(root, model.projectRoot)
            assertEquals(listOf(supportRoot, wrappedRoot), model.sourceRoots)
            assertEquals(listOf(jarPath, classesRoot), model.resolveBinaryClasspath)
            assertEquals(listOf(jarPath), model.displayBinaryClasspath)
            assertEquals("3.8.2", model.scalaVersion)
            assertEquals(listOf(jarPath), model.scalaCompilerClasspath)
            assertEquals("/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home", model.javaHomePath)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun `filters mill build output paths from displayed script dependencies`() {
        val root = Path.of("/tmp/project")
        val outputRoot = root.resolve(MillConstants.buildScriptOutputDirectory)
        val classpath = listOf(
            outputRoot.resolve("compile-resources"),
            Path.of("/tmp/coursier/cache/mill-core.jar"),
        )

        val filtered = MillBuildScriptSupport.filterDisplayBinaryClasspath(root, classpath)

        assertEquals(listOf(Path.of("/tmp/coursier/cache/mill-core.jar")), filtered)
    }

    @Test
    fun `parses show string list from pure json stdout`() {
        val output = """
            ["ref:v0:70b0c2a0:/tmp/project/examples/src","qref:v1:acedc6f5:/tmp/cache/scala3-library.jar"]
        """.trimIndent()

        val values = MillCommandLineUtil.parseStringList(output)

        assertEquals(
            listOf("/tmp/project/examples/src", "/tmp/cache/scala3-library.jar"),
            values,
        )
    }

    @Test
    fun `parses show string value from pure json stdout`() {
        val output = """
            "3.8.2"
        """.trimIndent()

        val value = MillCommandLineUtil.parseSingleStringValue(output)

        assertEquals("3.8.2", value)
    }

    @Test
    fun `parses resolved mill targets`() {
        val targets = MillCommandLineUtil.parseResolvedTargets(
            """
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
            Files.writeString(root.resolve("build.mill"), "// mill")
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
            Files.writeString(root.resolve("build.mill"), "// mill")

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
    fun `stores mill module files under idea modules directory`() {
        val projectRoot = Path.of("/tmp/project")
        val module = MillProjectResolverSupport.buildModuleData(projectRoot, projectRoot.resolve("foo"), "foo")

        assertEquals(projectRoot.resolve(MillConstants.moduleFilesDirectory).toString(), module.moduleFileDirectoryPath)
        assertEquals(projectRoot.toString(), module.linkedExternalProjectPath)
    }

    @Test
    fun `resolves production module outputs from local classpath`() {
        val module = MillDiscoveredModule("foo", "foo", Path.of("/tmp/project"), Path.of("/tmp/project/foo"))

        val outputs = MillModuleOutputResolver.resolveModuleOutputsFromLocalClasspath(
            module = module,
            localClasspath = listOf(
                Path.of("/tmp/project/out/foo/compile.dest/resources"),
                Path.of("/tmp/project/out/foo/compile.dest/classes"),
                Path.of("/tmp/coursier/cache/lib.jar"),
            ),
            settings = null,
        )

        assertEquals(Path.of("/tmp/project/out/foo/compile.dest/classes"), outputs?.classesOutputDirectory)
        assertEquals(Path.of("/tmp/project/out/foo/compile.dest/resources"), outputs?.resourcesOutputDirectory)
    }

    @Test
    fun `resolves test module outputs from local classpath`() {
        val module = MillDiscoveredModule(
            "foo.test",
            "foo.test",
            Path.of("/tmp/project"),
            Path.of("/tmp/project/foo/test"),
            productionModulePrefix = "foo",
        )

        val outputs = MillModuleOutputResolver.resolveModuleOutputsFromLocalClasspath(
            module = module,
            localClasspath = listOf(
                Path.of("/tmp/project/out/foo/test/compile.dest/classes"),
                Path.of("/tmp/project/out/foo/test/compile.dest/resources"),
            ),
            settings = null,
        )

        assertEquals(Path.of("/tmp/project/out/foo/test/compile.dest/classes"), outputs?.classesOutputDirectory)
        assertEquals(Path.of("/tmp/project/out/foo/test/compile.dest/resources"), outputs?.resourcesOutputDirectory)
    }

    @Test
    fun `respects custom mill output directory from execution settings`() {
        val settings = MillExecutionSettings().apply {
            addEnvironmentVariable("MILL_OUTPUT_DIR", ".mill-out")
        }
        val module = MillDiscoveredModule("foo", "foo", Path.of("/tmp/project"), Path.of("/tmp/project/foo"))

        val outputs = MillModuleOutputResolver.resolveModuleOutputsFromLocalClasspath(
            module = module,
            localClasspath = listOf(
                Path.of("/tmp/project/.mill-out/foo/compile.dest/classes"),
            ),
            settings = settings,
        )

        assertEquals(Path.of("/tmp/project/.mill-out/foo/compile.dest/classes"), outputs?.classesOutputDirectory)
    }

    @Test
    fun `ignores nested outputs outside the direct module compile destination`() {
        val module = MillDiscoveredModule("foo", "foo", Path.of("/tmp/project"), Path.of("/tmp/project/foo"))

        val outputs = MillModuleOutputResolver.resolveModuleOutputsFromLocalClasspath(
            module = module,
            localClasspath = listOf(
                Path.of("/tmp/project/out/foo/bar/compile.dest/classes"),
                Path.of("/tmp/project/out/foo/compile.dest/classes"),
            ),
            settings = null,
        )

        assertEquals(Path.of("/tmp/project/out/foo/compile.dest/classes"), outputs?.classesOutputDirectory)
    }

    @Test
    fun `applies external compiler output for production module`() {
        val projectRoot = Path.of("/tmp/project")
        val module = MillProjectResolverSupport.buildModuleData(projectRoot, projectRoot.resolve("foo"), "foo")

        MillModuleOutputResolver.applyModuleOutputs(
            moduleData = module,
            module = MillDiscoveredModule("foo", "foo", projectRoot, projectRoot.resolve("foo")),
            outputs = MillModuleOutputs(
                classesOutputDirectory = Path.of("/tmp/project/out/foo/compile.dest/classes"),
                resourcesOutputDirectory = Path.of("/tmp/project/out/foo/compile.dest/resources"),
            ),
        )

        assertEquals(false, module.isInheritProjectCompileOutputPath)
        assertEquals(
            "/tmp/project/out/foo/compile.dest/classes",
            module.getCompileOutputPath(ExternalSystemSourceType.SOURCE),
        )
        assertEquals(
            "/tmp/project/out/foo/compile.dest/resources",
            module.getCompileOutputPath(ExternalSystemSourceType.RESOURCE),
        )
    }

    @Test
    fun `applies external compiler output for test module`() {
        val projectRoot = Path.of("/tmp/project")
        val module = MillProjectResolverSupport.buildModuleData(projectRoot, projectRoot.resolve("foo/test"), "foo.test")

        MillModuleOutputResolver.applyModuleOutputs(
            moduleData = module,
            module = MillDiscoveredModule(
                "foo.test",
                "foo.test",
                projectRoot,
                projectRoot.resolve("foo/test"),
                productionModulePrefix = "foo",
            ),
            outputs = MillModuleOutputs(
                classesOutputDirectory = Path.of("/tmp/project/out/foo/test/compile.dest/classes"),
                resourcesOutputDirectory = Path.of("/tmp/project/out/foo/test/compile.dest/resources"),
            ),
        )

        assertEquals(false, module.isInheritProjectCompileOutputPath)
        assertEquals(
            "/tmp/project/out/foo/test/compile.dest/classes",
            module.getCompileOutputPath(ExternalSystemSourceType.TEST),
        )
        assertEquals(
            "/tmp/project/out/foo/test/compile.dest/resources",
            module.getCompileOutputPath(ExternalSystemSourceType.TEST_RESOURCE),
        )
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
    fun `does not assign synthetic task groups`() {
        val tasks = MillProjectResolverSupport.createTaskData(Path.of("/tmp/project"))

        assertTrue(tasks.all { it.group.isNullOrBlank() })
    }

    @Test
    fun `builds dotted task hierarchy from raw task names`() {
        val root = Path.of("/tmp/project")
        val tree = MillTaskTreeStructure.build(
            listOf(
                taskNode(root, "clean"),
                taskNode(root, "foo"),
                taskNode(root, "foo.test"),
                taskNode(root, "foo.test.testForked"),
                taskNode(root, "show foo.compileClasspath"),
            ),
        )

        assertEquals(listOf("clean", "foo", "show foo"), tree.map { it.displayName })
        assertEquals("foo", tree[1].taskNode?.data?.name)
        assertEquals(listOf("test"), tree[1].children.map { it.displayName })
        assertEquals("foo.test", tree[1].children.single().taskNode?.data?.name)
        assertEquals(
            listOf("testForked"),
            tree[1].children.single().children.map { it.displayName },
        )
        assertEquals(
            "show foo.compileClasspath",
            tree[2].children.single().taskNode?.data?.name,
        )
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

    private fun deleteRecursively(root: Path) {
        Files.walk(root)
            .sorted(Comparator.reverseOrder())
            .forEach(Files::deleteIfExists)
    }

    private fun taskNode(root: Path, name: String): DataNode<TaskData> {
        return DataNode(
            ProjectKeys.TASK,
            TaskData(
                MillConstants.systemId,
                name,
                root.toString(),
                "Task $name",
            ),
            null,
        )
    }
}
