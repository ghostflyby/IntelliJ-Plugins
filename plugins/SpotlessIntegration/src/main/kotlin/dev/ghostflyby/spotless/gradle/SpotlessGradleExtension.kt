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

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.toNioPathOrNull
import dev.ghostflyby.spotless.SpotlessDaemonHost
import dev.ghostflyby.spotless.SpotlessDaemonProvider
import org.jetbrains.plugins.gradle.settings.GradleSettings
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.Path
import kotlin.io.path.div

internal class SpotlessGradleExtension : SpotlessDaemonProvider {

    override fun isApplicableTo(
        project: Project,
    ): Boolean {
        val holder = project.service<SpotlessGradleStateHolder>()
        return GradleSettings.getInstance(project).linkedProjectsSettings
            .any { holder.isSpotlessEnabledForProjectDir(Path(it.externalProjectPath)) }
    }

    override suspend fun startDaemon(
        project: Project,
        externalProject: Path,
    ): SpotlessDaemonHost {
        val dir: Path = Files.createTempDirectory(null)
        val unixSocketPath = dir / "spotless-daemon.sock"
        val host = SpotlessDaemonHost.Unix(unixSocketPath)
        runGradleSpotlessDaemon(
            project,
            externalProject,
            unixSocketPath,
            host,
        )
        return host
    }

    override fun findExternalProjectPath(
        project: Project,
        virtualFile: VirtualFile,
    ): Path? {
        val ioPath = virtualFile.toNioPathOrNull() ?: return null
        val abs = ioPath.toAbsolutePath().normalize()

        val rootDirs = GradleSettings.getInstance(project).linkedProjectsSettings
            .mapNotNull { it.externalProjectPath }
            .map { Paths.get(it).toAbsolutePath().normalize() }

        return rootDirs
            .filter { abs.startsWith(it) }
            .maxByOrNull { it.nameCount }
    }

}
