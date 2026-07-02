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
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.navigation.ItemPresentation
import dev.ghostflyby.mill.MillConstants
import java.nio.file.Path
import javax.swing.Icon
import org.jetbrains.plugins.scala.icons.Icons

internal class MillBuildScriptLibraryRootsProvider : AdditionalLibraryRootsProvider() {
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        return MillBuildScriptRoots.displayBinaryRoots(project).map { (projectRoot, binaryRoots) ->
            MillBuildScriptSyntheticLibrary(projectRoot, binaryRoots)
        }
    }

    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        return MillBuildScriptRoots.watchedRoots(project)
    }
}

private class MillBuildScriptSyntheticLibrary(
    private val projectRoot: Path,
    private val binaryRoots: List<VirtualFile>,
) : SyntheticLibrary("mill-build-script:$projectRoot", null), ItemPresentation {
    override fun getSourceRoots(): Collection<VirtualFile> = emptyList()

    override fun getBinaryRoots(): Collection<VirtualFile> = binaryRoots

    override fun getPresentableText(): String {
        val projectName = projectRoot.fileName?.toString().orEmpty().ifBlank { "Mill" }
        return "$projectName/${MillConstants.buildScriptFileName}"
    }

    override fun getLocationString(): String = projectRoot.toString()

    override fun getIcon(unused: Boolean): Icon = Icons.MILL_FILE

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MillBuildScriptSyntheticLibrary) return false
        return projectRoot == other.projectRoot && binaryRoots == other.binaryRoots
    }

    override fun hashCode(): Int {
        var result = projectRoot.hashCode()
        result = 31 * result + binaryRoots.hashCode()
        return result
    }
}
