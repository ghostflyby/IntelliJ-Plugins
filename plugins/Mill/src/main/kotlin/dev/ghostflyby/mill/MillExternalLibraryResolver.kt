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

import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Files
import java.nio.file.Path

internal object MillExternalLibraryResolver {
    fun resolveBinaryLibraryPaths(
        module: MillDiscoveredModule,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
    ): List<Path> {
        val metadataTargets = listOf(
            "${module.targetPrefix}.resolvedMvnDeps",
            "${module.targetPrefix}.resolvedIvyDeps",
        )
        metadataTargets.forEach { showTarget ->
            val paths = MillShowTargetPathResolver.resolvePaths(
                root = module.projectRoot,
                settings = settings,
                taskId = taskId,
                listener = listener,
                showTarget = showTarget,
                failureContext = "external library resolution",
                reportFailures = false,
            ).asSequence()
                .filter(Files::isRegularFile)
                .filter(::isBinaryLibraryPath)
                .distinct()
                .toList()
            if (paths.isNotEmpty()) {
                return paths
            }
        }

        val fallbackPaths = MillClasspathResolver.resolveBinaryLibraryPaths(
            root = module.projectRoot,
            settings = settings,
            taskId = taskId,
            listener = listener,
            classpathTarget = "${module.targetPrefix}.compileClasspath",
        )
        if (fallbackPaths.isNotEmpty()) {
            listener.onTaskOutput(
                taskId,
                "Mill external libraries for `${module.targetPrefix}` fell back to compileClasspath.\n",
                ProcessOutputType.STDOUT,
            )
        }
        return fallbackPaths
    }

    private fun isBinaryLibraryPath(path: Path): Boolean {
        val fileName = path.fileName?.toString().orEmpty().lowercase()
        return fileName.endsWith(".jar") || fileName.endsWith(".zip")
    }
}
