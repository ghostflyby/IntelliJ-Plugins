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

package dev.ghostflyby.mcp.scope

import com.intellij.openapi.module.Module
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.SearchScope
import java.security.MessageDigest

internal fun scopeShapeOf(scope: SearchScope): ScopeShape {
    return when (scope) {
        is GlobalSearchScope -> ScopeShape.GLOBAL
        is LocalSearchScope -> ScopeShape.LOCAL
        else -> ScopeShape.MIXED
    }
}

internal fun sha256ShortHash(text: String, length: Int): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { b -> "%02x".format(b) }.take(length)
}

internal fun ModuleScopeFlavor.scopeFor(module: Module): GlobalSearchScope {
    return when (this) {
        ModuleScopeFlavor.MODULE -> module.moduleScope
        ModuleScopeFlavor.MODULE_WITH_DEPENDENCIES -> module.moduleWithDependenciesScope
        ModuleScopeFlavor.MODULE_WITH_LIBRARIES -> module.moduleWithLibrariesScope
        ModuleScopeFlavor.MODULE_WITH_DEPENDENCIES_AND_LIBRARIES -> module.getModuleWithDependenciesAndLibrariesScope(
            true,
        )
    }
}
