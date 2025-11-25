/*
 * Copyright (c) 2025 ghostflyby
 * SPDX-FileCopyrightText: 2025 ghostflyby
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

package dev.ghostflyby.spotless.gradle

import com.intellij.openapi.editor.Document
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiFile
import org.jetbrains.plugins.gradle.settings.GradleSettings
import org.jetbrains.plugins.gradle.util.GradleConstants
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path

internal val Module.gradleRoot
    get(): Path? {
        val manager = ExternalSystemModulePropertyManager.getInstance(this)
        if (manager.getExternalSystemId() != GradleConstants.SYSTEM_ID.id) return null

        val moduleExternalPath = manager.getRootProjectPath() ?: return null

        return Path(moduleExternalPath)
    }

internal fun Project.findGradleRoot(path: Path): Path? {
    val ioPath = path.toAbsolutePath().normalize()

    return LocalFileSystem.getInstance()
        .findFileByIoFile(ioPath.toFile())?.findGradleRoot(this) ?: gradleRootForPath(ioPath)
}

internal fun Document.findGradleRoot(project: Project): Path? {
    return FileDocumentManager.getInstance()
        .getFile(this)?.findGradleRoot(project)
}

internal fun VirtualFile.findGradleRoot(project: Project): Path? {
    val fileIndex = ProjectRootManager.getInstance(project).fileIndex
    val module = fileIndex.getModuleForFile(this)  // 不关心 library sources 的话这个就够用

    return module?.gradleRoot ?: toNioPathOrNull()?.let { project.gradleRootForPath(it) }
}

internal fun PsiFile.findGradleRoot(): Path? {
    val project = this.project

    return virtualFile?.findGradleRoot(project)
}


private fun Project.gradleRootForPath(ioPath: Path): Path? {
    val abs = ioPath.toAbsolutePath().normalize()

    val rootDirs = GradleSettings.getInstance(this).linkedProjectsSettings
        .mapNotNull { it.externalProjectPath }
        .map { Paths.get(it).toAbsolutePath().normalize() }

    return rootDirs
        .filter { abs.startsWith(it) }
        .maxByOrNull { it.nameCount }
}
