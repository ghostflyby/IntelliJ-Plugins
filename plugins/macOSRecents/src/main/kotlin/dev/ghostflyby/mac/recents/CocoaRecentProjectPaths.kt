/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
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

package dev.ghostflyby.mac.recents

import java.net.URI
import java.nio.file.Path as NioPath
import kotlin.io.path.Path

internal fun collectTargetUris(
    recentPaths: List<String>,
    startupProjectPaths: Set<String> = emptySet(),
): List<URI> {
    val recentUris = recentPaths.mapNotNull(::pathToUriOrNull)
    val startupUris = startupProjectPaths
        .asSequence()
        .mapNotNull(::pathToUriOrNull)
        .filterNot(recentUris::contains)
        .toList()
    if (startupUris.isEmpty()) {
        return recentUris
    }
    return recentUris + startupUris
}

internal fun normalizeRecentUris(rawUris: List<URI>): List<URI> {
    val deduplicatedUris = LinkedHashSet<URI>()
    rawUris.forEach(deduplicatedUris::add)
    return deduplicatedUris.toList().asReversed()
}

internal fun canAppendIncrementally(currentUris: List<URI>, desiredUris: List<URI>): Boolean {
    if (currentUris.isEmpty()) {
        return false
    }
    if (desiredUris.size < currentUris.size) {
        return false
    }
    return desiredUris.subList(0, currentUris.size) == currentUris
}

internal fun startupProjectPathToEntryOrNull(path: String): Pair<String, String>? {
    val projectKey = projectIdentityKeyOrNull(path) ?: return null
    return projectKey to path
}

internal fun projectIdentityKeyOrNull(path: String): String? {
    val rawPath = runCatching { Path(path) }.getOrNull() ?: return null
    return normalizeProjectIdentityPath(rawPath).toSystemIndependentPath()
}

internal fun canonicalProjectIdentityKeyOrNull(path: String): String? {
    val rawPath = runCatching { Path(path) }.getOrNull() ?: return null
    val projectPath = normalizeProjectIdentityPath(rawPath)
    val canonicalPath = runCatching { projectPath.toRealPath() }.getOrElse { projectPath }
    return canonicalPath.toSystemIndependentPath()
}

internal fun findStartupProjectsToRemove(
    recentPaths: List<String>,
    startupProjects: Map<String, String>,
): Set<String> {
    val recentProjectKeys = recentPaths.asSequence()
        .mapNotNull(::canonicalProjectIdentityKeyOrNull)
        .toSet()
    if (recentProjectKeys.isEmpty()) {
        return emptySet()
    }

    return startupProjects.asSequence()
        .filter { (_, startupProjectPath) ->
            canonicalProjectIdentityKeyOrNull(startupProjectPath) in recentProjectKeys
        }
        .map { (projectKey, _) -> projectKey }
        .toSet()
}

private fun pathToUriOrNull(path: String): URI? {
    return runCatching { Path(path).toUri() }.getOrNull()
}

private fun normalizeProjectIdentityPath(path: NioPath): NioPath {
    val absolutePath = path.toAbsolutePath().normalize()
    return absolutePath.toDirectoryBasedProjectRootOrSelf()
}

private fun NioPath.toDirectoryBasedProjectRootOrSelf(): NioPath {
    if (fileName?.toString() != DIRECTORY_BASED_PROJECT_FILE_NAME) {
        return this
    }
    val ideaDirectory = parent ?: return this
    if (ideaDirectory.fileName?.toString() != IDEA_DIRECTORY_NAME) {
        return this
    }
    return ideaDirectory.parent ?: this
}

private fun NioPath.toSystemIndependentPath(): String {
    return toString().replace('\\', '/')
}

private const val DIRECTORY_BASED_PROJECT_FILE_NAME = "misc.xml"
private const val IDEA_DIRECTORY_NAME = ".idea"
