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

import com.intellij.openapi.project.Project
import kotlinx.serialization.Serializable

@Serializable
internal enum class ScopeQuickPreset {
    PROJECT_FILES,
    ALL_PLACES,
    OPEN_FILES,
    PROJECT_AND_LIBRARIES,
    PROJECT_PRODUCTION_FILES,
    PROJECT_TEST_FILES,
}

internal fun ScopeQuickPreset.toStandardScopeId(): String {
    return when (this) {
        ScopeQuickPreset.PROJECT_FILES -> "Project Files"
        ScopeQuickPreset.ALL_PLACES -> "All Places"
        ScopeQuickPreset.OPEN_FILES -> "Open Files"
        ScopeQuickPreset.PROJECT_AND_LIBRARIES -> "Project and Libraries"
        ScopeQuickPreset.PROJECT_PRODUCTION_FILES -> "Project Production Files"
        ScopeQuickPreset.PROJECT_TEST_FILES -> "Project Test Files"
    }
}

internal suspend fun buildStandardScopeDescriptor(
    project: Project,
    standardScopeId: String,
    allowUiInteractiveScopes: Boolean,
): ScopeProgramDescriptorDto {
    val request = ScopeResolveRequestDto(
        atoms = listOf(
            ScopeAtomDto(
                atomId = "std",
                kind = ScopeAtomKind.STANDARD,
                standardScopeId = standardScopeId,
            ),
        ),
        tokens = listOf(
            ScopeProgramTokenDto(
                op = ScopeProgramOp.PUSH_ATOM,
                atomId = "std",
            ),
        ),
        strict = true,
        allowUiInteractiveScopes = allowUiInteractiveScopes,
        nonStrictDefaultFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
    )
    return ScopeResolverService.getInstance(project).compileProgramDescriptor(project, request)
}

internal suspend fun buildPresetScopeDescriptor(
    project: Project,
    preset: ScopeQuickPreset,
    allowUiInteractiveScopes: Boolean,
): ScopeProgramDescriptorDto {
    return buildStandardScopeDescriptor(
        project = project,
        standardScopeId = preset.toStandardScopeId(),
        allowUiInteractiveScopes = allowUiInteractiveScopes,
    )
}
