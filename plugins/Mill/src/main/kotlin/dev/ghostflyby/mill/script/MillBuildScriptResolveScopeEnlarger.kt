/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mill.script

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.ResolveScopeEnlarger
import com.intellij.psi.search.NonClasspathDirectoriesScope
import com.intellij.psi.search.SearchScope

internal class MillBuildScriptResolveScopeEnlarger : ResolveScopeEnlarger() {
    override fun getAdditionalResolveScope(file: VirtualFile, project: Project): SearchScope? {
        val roots = MillBuildScriptRoots.resolveRoots(project, file)
        if (roots.isEmpty()) {
            return null
        }
        return NonClasspathDirectoriesScope.compose(roots)
    }
}
