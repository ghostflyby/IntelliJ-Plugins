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

package dev.ghostflyby.mill.script

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.JarFileSystem
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import dev.ghostflyby.mill.project.MillProjectResolverSupport
import java.nio.file.Files
import java.nio.file.Path

internal object MillBuildScriptRoots {
    fun resolveRoots(project: Project, file: VirtualFile): List<VirtualFile> {
        val model = findModel(project, file) ?: return emptyList()
        return model.resolveRoots.mapNotNull(::findResolveRoot).distinct()
    }

    fun displayBinaryRoots(project: Project): List<Pair<Path, List<VirtualFile>>> {
        return MillBuildScriptSupport.candidateProjectRoots(project).mapNotNull { projectRoot ->
            val model = MillBuildScriptSupport.loadModel(projectRoot) ?: return@mapNotNull null
            val binaryRoots = model.displayBinaryClasspath.mapNotNull(::findBinaryRoot).distinct()
            if (binaryRoots.isEmpty()) {
                return@mapNotNull null
            }
            projectRoot to binaryRoots
        }
    }

    fun watchedRoots(project: Project): List<VirtualFile> {
        return MillBuildScriptSupport.candidateProjectRoots(project)
            .flatMap { projectRoot ->
                listOf(
                    MillBuildScriptSupport.buildScriptFile(projectRoot),
                    MillBuildScriptSupport.outputDirectory(projectRoot),
                )
            }
            .mapNotNull(::findLocalFile)
            .distinct()
    }

    private fun findModel(project: Project, file: VirtualFile): MillBuildScriptModel? {
        if (!MillBuildScriptSupport.isBuildScriptFile(file)) {
            return null
        }
        val projectRoot = runCatching { MillProjectResolverSupport.findProjectRoot(file.path) }.getOrNull() ?: return null
        if (!MillProjectResolverSupport.hasMillConfig(projectRoot.toString())) {
            return null
        }
        return MillBuildScriptSupport.loadModel(projectRoot)
    }

    private fun findResolveRoot(path: Path): VirtualFile? {
        return if (Files.isDirectory(path)) findLocalFile(path) else findBinaryRoot(path)
    }

    private fun findBinaryRoot(path: Path): VirtualFile? {
        if (Files.isDirectory(path)) {
            return findLocalFile(path)
        }
        if (!Files.isRegularFile(path)) {
            return null
        }
        val localFile = findLocalFile(path) ?: return null
        return when (path.fileName?.toString()?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "jar", "zip" -> JarFileSystem.getInstance().getJarRootForLocalFile(localFile)
            else -> localFile
        }
    }

    private fun findLocalFile(path: Path): VirtualFile? {
        return VirtualFileManager.getInstance().findFileByNioPath(path)
            ?: LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }
}
