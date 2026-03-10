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

import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListener
import java.nio.file.Files
import java.nio.file.Path

internal object MillClasspathResolver {
    internal fun parsePathList(output: String): List<String> = MillShowTargetPathResolver.parsePathList(output)

    fun resolveBinaryLibraryPaths(
        root: Path,
        settings: MillExecutionSettings?,
        taskId: ExternalSystemTaskId,
        listener: ExternalSystemTaskNotificationListener,
        classpathTarget: String = "__.compileClasspath",
    ): List<Path> {
        return MillShowTargetPathResolver.resolvePaths(
            root = root,
            settings = settings,
            taskId = taskId,
            listener = listener,
            showTarget = classpathTarget,
            failureContext = "dependency resolution",
        )
            .asSequence()
            .filter(Files::isRegularFile)
            .filter(::isBinaryLibraryPath)
            .distinct()
            .toList()
    }

    private fun isBinaryLibraryPath(path: Path): Boolean {
        val fileName = path.fileName?.toString().orEmpty().lowercase()
        return fileName.endsWith(".jar") || fileName.endsWith(".zip")
    }
}
