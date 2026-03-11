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

import com.intellij.openapi.externalSystem.model.ExternalSystemException
import com.intellij.openapi.externalSystem.model.project.*
import com.intellij.openapi.externalSystem.model.task.TaskData
import com.intellij.openapi.roots.DependencyScope
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

public object MillProjectResolverSupport {
    @JvmField
    public val moduleContentDirectoryNames: Set<String> = linkedSetOf("src", "resources", "test/src", "test/resources")

    @JvmStatic
    public fun isProjectFileName(fileName: String): Boolean {
        return fileName in MillConstants.projectFileNames
    }

    @JvmStatic
    public fun findProjectRoot(projectPath: String): Path {
        val initialPath = Path.of(projectPath).toAbsolutePath().normalize()
        var current = if (Files.isDirectory(initialPath)) initialPath else initialPath.parent ?: initialPath

        while (true) {
            if (containsMillConfig(current)) {
                return current
            }
            current = current.parent ?: break
        }

        throw ExternalSystemException(
            "Mill project root not found for '$projectPath'. Expected one of ${MillConstants.configFileNames.joinToString()}.",
        )
    }

    @JvmStatic
    public fun presentableProjectName(targetProjectPath: String, rootProjectPath: String?): String {
        val root = runCatching { findProjectRoot(rootProjectPath ?: targetProjectPath) }.getOrElse {
            val target = Path.of(targetProjectPath).toAbsolutePath().normalize()
            if (Files.isDirectory(target)) target else target.parent ?: target
        }
        return root.fileName?.toString().orEmpty().ifBlank { "Mill" }
    }

    @JvmStatic
    public fun buildProjectData(root: Path): ProjectData {
        val projectName = root.fileName?.toString().orEmpty().ifBlank { "Mill" }
        return ProjectData(MillConstants.systemId, projectName, root.toString(), root.toString()).apply {
            description = "Imported from Mill build files."
        }
    }

    @JvmStatic
    public fun buildModuleData(projectRoot: Path, moduleDir: Path, moduleName: String): ModuleData {
        return ModuleData(
            "$moduleName:${moduleDir.toAbsolutePath()}",
            MillConstants.systemId,
            MillConstants.moduleTypeId,
            moduleName,
            moduleDir.toString(),
            projectRoot.toString(),
        ).apply {
            this.moduleName = moduleName
        }
    }

    @JvmStatic
    public fun buildContentRoot(root: Path): ContentRootData {
        return buildContentRoot(root, detectSourceRoots(root))
    }

    @JvmStatic
    public fun buildContentRoot(root: Path, sourceRoots: Collection<Pair<ExternalSystemSourceType, Path>>): ContentRootData {
        return ContentRootData(MillConstants.systemId, root.toString()).apply {
            sourceRoots
                .distinct()
                .forEach { (type, path) ->
                storePath(type, path.toString())
            }
            detectExcludedRoots(root).forEach { path ->
                storePath(ExternalSystemSourceType.EXCLUDED, path.toString())
            }
        }
    }

    @JvmStatic
    public fun buildLibraryDependency(ownerModule: ModuleData, binaryPath: Path): LibraryDependencyData {
        val libraryName = binaryPath.fileName?.toString().orEmpty().ifBlank { binaryPath.toString() }
        val libraryData = LibraryData(MillConstants.systemId, libraryName).apply {
            addPath(LibraryPathType.BINARY, binaryPath.toString())
        }
        return LibraryDependencyData(ownerModule, libraryData, LibraryLevel.PROJECT).apply {
            scope = DependencyScope.COMPILE
        }
    }

    @JvmStatic
    public fun buildModuleDependency(ownerModule: ModuleData, targetModule: ModuleData): ModuleDependencyData {
        return ModuleDependencyData(ownerModule, targetModule).apply {
            scope = DependencyScope.COMPILE
        }
    }

    @JvmStatic
    public fun createTaskData(root: Path): List<TaskData> {
        return createDefaultTaskData(root)
    }

    internal fun createTaskData(
        root: Path,
        discoveredModules: Collection<MillDiscoveredModule>,
        resolvedTargets: Collection<String>,
    ): List<TaskData> {
        val tasks = linkedMapOf<String, TaskData>()
        createDefaultTaskData(root).forEach { task ->
            tasks.putIfAbsent(task.name, task)
        }

        val modulesByPrefix = discoveredModules.associateBy { it.targetPrefix }
        resolvedTargets.asSequence()
            .filter(::isActionableTarget)
            .forEach { target ->
                val targetPrefix = target.substringBeforeLast('.', missingDelimiterValue = "")
                val module = modulesByPrefix[targetPrefix]
                val (description, group, isTest) = describeTask(target, module)
                tasks.putIfAbsent(target, task(root, target, description, group, isTest))
            }

        discoveredModules.forEach { module ->
            val moduleLabel = presentableModuleLabel(module)
            val compileClasspathTarget = module.queryTarget("compileClasspath")
            tasks.putIfAbsent(
                "show $compileClasspathTarget",
                task(
                    root = root,
                    name = "show $compileClasspathTarget",
                    description = "Print the compile classpath for $moduleLabel",
                    group = "help",
                ),
            )
        }

        return tasks.values.toList()
    }

    internal fun hasMillConfig(projectPath: String?): Boolean {
        val path = projectPath?.let(Path::of)?.toAbsolutePath()?.normalize() ?: return false
        return containsMillConfig(path)
    }

    internal fun findLinkedProjectPathForContext(
        contextPath: String,
        linkedProjectPaths: Collection<String>,
    ): String? {
        val normalizedContextPath = Path.of(contextPath).toAbsolutePath().normalize()
        return linkedProjectPaths
            .asSequence()
            .map { linkedProjectPath ->
                linkedProjectPath to Path.of(linkedProjectPath).toAbsolutePath().normalize()
            }
            .sortedByDescending { (_, linkedProjectPath) -> linkedProjectPath.nameCount }
            .firstOrNull { (_, linkedProjectPath) -> normalizedContextPath.startsWith(linkedProjectPath) }
            ?.first
    }

    @JvmStatic
    public fun findAffectedExternalProjectPath(
        changedFileOrDirPath: String,
        linkedProjectPaths: Collection<String>,
    ): String? {
        val changedPath = Path.of(changedFileOrDirPath).toAbsolutePath().normalize()
        val changedFileName = changedPath.fileName?.toString()
        val isWatchedFile = changedFileName != null && changedFileName in MillConstants.configFileNames

        return linkedProjectPaths.firstOrNull { linkedProjectPath ->
            val root = Path.of(linkedProjectPath).toAbsolutePath().normalize()
            when {
                isWatchedFile -> changedPath.startsWith(root)
                Files.isDirectory(changedPath) && changedPath == root -> true
                else -> false
            }
        }
    }

    @JvmStatic
    public fun findAffectedExternalProjectFiles(projectPath: String): List<File> {
        val root = findProjectRoot(projectPath)
        return MillConstants.configFileNames
            .map(root::resolve)
            .filter(Files::exists)
            .map(Path::toFile)
    }

    private fun containsMillConfig(path: Path): Boolean {
        return MillConstants.configFileNames.any { Files.exists(path.resolve(it)) }
    }

    private fun detectSourceRoots(root: Path): List<Pair<ExternalSystemSourceType, Path>> {
        val candidates = linkedMapOf(
            root.resolve("src") to ExternalSystemSourceType.SOURCE,
            root.resolve("resources") to ExternalSystemSourceType.RESOURCE,
            root.resolve("test/src") to ExternalSystemSourceType.TEST,
            root.resolve("test/resources") to ExternalSystemSourceType.TEST_RESOURCE,
        )
        return candidates.entries
            .filter { (path, _) -> Files.isDirectory(path) }
            .map { it.value to it.key }
    }

    private fun detectExcludedRoots(root: Path): List<Path> {
        return listOf(".idea", ".bsp", ".mill-ammonite", "out", "target")
            .map(root::resolve)
            .filter(Files::isDirectory)
    }

    private fun createDefaultTaskData(root: Path): List<TaskData> {
        return listOf(
            task(
                root,
                "resolve ${MillConstants.moduleDiscoveryQuery}",
                "Resolve available Mill targets under ${MillConstants.moduleDiscoveryQuery}",
                group = "help",
            ),
            task(root, "__.compile", "Compile all Mill modules", group = "build"),
            task(root, "__.test", "Run tests for all Mill modules", group = "verification", isTest = true),
            task(root, "__.runBackground", "Run the default background target", group = "application"),
            task(root, "show __.compileClasspath", "Print the aggregate compile classpath", group = "help"),
        )
    }

    private fun isActionableTarget(target: String): Boolean {
        return when (target.substringAfterLast('.', missingDelimiterValue = target)) {
            "compile", "test", "run", "runBackground", "assembly", "jar" -> true
            else -> false
        }
    }

    private fun describeTask(
        target: String,
        module: MillDiscoveredModule?,
    ): Triple<String, String, Boolean> {
        val action = target.substringAfterLast('.', missingDelimiterValue = target)
        val moduleLabel = presentableModuleLabel(module)
        return when (action) {
            "compile" -> Triple("Compile $moduleLabel", "build", false)
            "test" -> Triple("Run tests for $moduleLabel", "verification", true)
            "run", "runBackground" -> Triple("Run $moduleLabel", "application", false)
            "assembly", "jar" -> Triple("Build an artifact for $moduleLabel", "build", false)
            else -> Triple("Run $target", "other", false)
        }
    }

    private fun presentableModuleLabel(module: MillDiscoveredModule?): String {
        return when (module?.targetPrefix) {
            null -> "the selected Mill target"
            MillConstants.rootModulePrefix -> "the root Mill module"
            else -> "Mill module `${module.displayName}`"
        }
    }

    private fun task(
        root: Path,
        name: String,
        description: String,
        group: String,
        isTest: Boolean = false,
    ): TaskData {
        return TaskData(
            MillConstants.systemId,
            name,
            root.toString(),
            description,
        ).apply {
            this.group = group
            type = "mill"
            isJvm = true
            setTest(isTest)
            isJvmTest = isTest
        }
    }
}
