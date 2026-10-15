/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.script

import com.intellij.navigation.ItemPresentation
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import dev.ghostflyby.mill.MillConstants
import org.jetbrains.plugins.scala.icons.Icons
import java.nio.file.Path
import javax.swing.Icon

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
