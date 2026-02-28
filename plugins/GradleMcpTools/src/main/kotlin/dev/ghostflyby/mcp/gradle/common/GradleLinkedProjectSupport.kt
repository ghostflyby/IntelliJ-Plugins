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

package dev.ghostflyby.mcp.gradle.common

import com.intellij.mcpserver.McpExpectedError
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal data class LinkedGradleProject(
    val path: Path,
    val gradleJvm: String?,
)

internal fun getLinkedGradleProjects(project: Project): List<LinkedGradleProject> {
    val linkedProjects = GradleSettings.getInstance(project).linkedProjectsSettings
        .asSequence()
        .filter { it.externalProjectPath.isNotBlank() }
        .map { projectSettings ->
            LinkedGradleProject(
                path = toPath(projectSettings.externalProjectPath, "linked Gradle project path"),
                gradleJvm = projectSettings.gradleJvm,
            )
        }
        .distinctBy { it.path.normalize().toString() }
        .toList()
    if (linkedProjects.isEmpty()) {
        throw McpExpectedError("No linked Gradle projects found in current IDE project.")
    }
    return linkedProjects
}

internal fun getLinkedGradleProjectPaths(project: Project): List<Path> = getLinkedGradleProjects(project).map { it.path }

internal fun selectTargetPaths(linkedProjectPaths: List<Path>, externalProjectPath: String?): List<Path> {
    if (externalProjectPath.isNullOrBlank()) return linkedProjectPaths
    val requestedPath = toPath(externalProjectPath, "externalProjectPath")
    return listOf(matchLinkedProjectPath(linkedProjectPaths, requestedPath))
}

internal fun selectTargetProjectPath(
    project: Project,
    linkedProjectPaths: List<Path>,
    externalProjectPath: String?,
): Path {
    if (!externalProjectPath.isNullOrBlank()) {
        return matchLinkedProjectPath(linkedProjectPaths, toPath(externalProjectPath, "externalProjectPath"))
    }
    if (linkedProjectPaths.size == 1) {
        return linkedProjectPaths.first()
    }

    val basePath = project.basePath?.let(::toPathOrNull)
    if (basePath != null) {
        val normalizedBasePath = basePath.normalize()
        val matched = linkedProjectPaths.firstOrNull { it.normalize() == normalizedBasePath }
        if (matched != null) {
            return matched
        }
    }

    throw McpExpectedError(
        "Multiple linked Gradle projects found. Specify externalProjectPath. " +
            "Available values: ${linkedProjectPaths.joinToString { it.toString() }}",
    )
}

internal fun matchLinkedProjectPath(linkedProjectPaths: List<Path>, requestedPath: Path): Path {
    val normalizedRequestedPath = requestedPath.normalize()
    val normalizedLinkedProjectPaths = linkedProjectPaths.map { it to it.normalize() }

    val exactMatches = normalizedLinkedProjectPaths
        .filter { (_, linkedPath) -> linkedPath == normalizedRequestedPath }
        .map { (originalPath, _) -> originalPath }
    if (exactMatches.any()) {
        return exactMatches.singleOrNull() ?: throw McpExpectedError(
            "Ambiguous Gradle project path '$requestedPath'. " +
                "Matched values: ${exactMatches.joinToString { it.toString() }}. " +
                "Please specify a full linked path.",
        )
    }

    val suffixMatches = normalizedLinkedProjectPaths
        .filter { (_, linkedPath) -> linkedPath.endsWith(normalizedRequestedPath) }
        .map { (originalPath, _) -> originalPath }
    if (suffixMatches.any()) {
        return suffixMatches.singleOrNull() ?: throw McpExpectedError(
            "Ambiguous Gradle project path '$requestedPath'. " +
                "Matched values: ${suffixMatches.joinToString { it.toString() }}. " +
                "Please specify a full linked path.",
        )
    }

    throw McpExpectedError(
        "Gradle project path '$requestedPath' is not linked. " +
            "Available values: ${linkedProjectPaths.joinToString { it.toString() }}",
    )
}

internal suspend fun isRootGradleProject(path: Path, allLinkedPaths: Set<Path>): Boolean {
    val normalizedPath = path.normalize()
    val isRoot = withContext(Dispatchers.IO) {
        Files.exists(path.resolve("settings.gradle.kts")) || Files.exists(path.resolve("settings.gradle"))
    }
    if (isRoot) return true
    return allLinkedPaths.none { otherPath ->
        otherPath != normalizedPath && normalizedPath.startsWith(otherPath)
    }
}

internal suspend fun detectBuildFile(path: Path): Path? {
    val candidates = listOf("build.gradle.kts", "build.gradle")
    return candidates.asSequence()
        .map { path.resolve(it) }
        .firstOrNull { withContext(Dispatchers.IO) { Files.isRegularFile(it) } }
}

internal fun toPath(pathText: String, paramName: String): Path =
    toPathOrNull(pathText) ?: throw McpExpectedError("Invalid path for $paramName: '$pathText'")

internal fun toPathOrNull(pathText: String): Path? {
    return try {
        Path.of(pathText).normalize()
    } catch (_: InvalidPathException) {
        null
    }
}
