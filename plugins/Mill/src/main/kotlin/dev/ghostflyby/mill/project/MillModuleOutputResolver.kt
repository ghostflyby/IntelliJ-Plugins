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

package dev.ghostflyby.mill.project

import com.intellij.openapi.externalSystem.model.project.ExternalSystemSourceType
import com.intellij.openapi.externalSystem.model.project.ModuleData
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import dev.ghostflyby.mill.MillConstants
import dev.ghostflyby.mill.command.MillCommandLineUtil
import dev.ghostflyby.mill.settings.MillExecutionSettings
import java.nio.file.Path

internal data class MillModuleOutputs(
    val classesOutputDirectory: Path,
    val resourcesOutputDirectory: Path?,
)

internal object MillModuleOutputResolver {
    private const val outputDirectoryEnvironmentVariable: String = "MILL_OUTPUT_DIR"

    fun resolveModuleOutputs(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): MillModuleOutputs? {
        if (settings?.useMillMetadataDuringImport == false) {
            return null
        }
        val localClasspath = MillCommandLineUtil.showPaths(
            projectRoot = module.projectRoot,
            settings = settings,
            showTarget = module.queryTarget("localClasspath"),
        )
        if (!localClasspath.command.isSuccess) {
            localClasspath.command.reportFailure(taskId, listener, "module output resolution", reportFailures = false)
            return null
        }
        return resolveModuleOutputsFromLocalClasspath(module, localClasspath.value, settings)
    }

    fun applyModuleOutputs(
        moduleData: ModuleData,
        module: MillDiscoveredModule,
        outputs: MillModuleOutputs,
    ) {
        moduleData.isInheritProjectCompileOutputPath = false
        moduleData.useExternalCompilerOutput(true)
        val classesOutputPath = outputs.classesOutputDirectory.toString()
        val resourcesOutputPath = outputs.resourcesOutputDirectory?.toString() ?: classesOutputPath
        if (module.isTestModule) {
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.TEST, classesOutputPath)
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.TEST_GENERATED, classesOutputPath)
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.TEST_RESOURCE, resourcesOutputPath)
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.TEST_RESOURCE_GENERATED, resourcesOutputPath)
        } else {
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.SOURCE, classesOutputPath)
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.SOURCE_GENERATED, classesOutputPath)
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.RESOURCE, resourcesOutputPath)
            moduleData.setExternalCompilerOutputPath(ExternalSystemSourceType.RESOURCE_GENERATED, resourcesOutputPath)
        }
    }

    internal fun resolveModuleOutputsFromLocalClasspath(
        module: MillDiscoveredModule,
        localClasspath: Collection<Path>,
        settings: MillExecutionSettings?,
    ): MillModuleOutputs? {
        val moduleOutputRoot = resolveModuleOutputRoot(module, settings)
        val normalizedPaths = localClasspath.asSequence()
            .map(Path::toAbsolutePath)
            .map(Path::normalize)
            .distinct()
            .toList()
        val classesOutputDirectory = normalizedPaths.firstOrNull { candidate ->
            isDirectCompileOutputDirectory(moduleOutputRoot, candidate, "classes")
        } ?: return null
        val resourcesOutputDirectory = normalizedPaths.firstOrNull { candidate ->
            isDirectCompileOutputDirectory(moduleOutputRoot, candidate, "resources")
        }
        return MillModuleOutputs(
            classesOutputDirectory = classesOutputDirectory,
            resourcesOutputDirectory = resourcesOutputDirectory,
        )
    }

    internal fun resolveOutputRoot(projectRoot: Path, settings: MillExecutionSettings?): Path {
        val configuredOutputRoot = settings?.env?.get(outputDirectoryEnvironmentVariable)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return projectRoot.resolve("out").normalize()
        val outputPath = runCatching { Path.of(configuredOutputRoot) }.getOrNull()
            ?: return projectRoot.resolve("out").normalize()
        return if (outputPath.isAbsolute) {
            outputPath.normalize()
        } else {
            projectRoot.resolve(outputPath).normalize()
        }
    }

    private fun resolveModuleOutputRoot(module: MillDiscoveredModule, settings: MillExecutionSettings?): Path {
        val outputRoot = resolveOutputRoot(module.projectRoot, settings)
        return when (module.targetPrefix) {
            MillConstants.rootModulePrefix -> outputRoot
            else -> outputRoot.resolve(module.targetPrefix.replace('.', '/')).normalize()
        }
    }

    private fun isDirectCompileOutputDirectory(
        moduleOutputRoot: Path,
        candidate: Path,
        leafDirectoryName: String,
    ): Boolean {
        if (!candidate.startsWith(moduleOutputRoot)) {
            return false
        }
        val relativePath = runCatching { moduleOutputRoot.relativize(candidate) }.getOrNull() ?: return false
        return relativePath.nameCount == 2 &&
            relativePath.getName(0).toString() == "compile.dest" &&
            relativePath.getName(1).toString() == leafDirectoryName
    }
}
