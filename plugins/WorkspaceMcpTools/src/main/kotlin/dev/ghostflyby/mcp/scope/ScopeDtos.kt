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

import kotlinx.serialization.Serializable

@Serializable
internal enum class ScopeAtomKind {
    STANDARD,
    MODULE,
    NAMED_SCOPE,
    PATTERN,
    DIRECTORY,
    FILES,
    PROVIDER_SCOPE,
}

@Serializable
internal enum class ModuleScopeFlavor {
    MODULE,
    MODULE_WITH_DEPENDENCIES,
    MODULE_WITH_LIBRARIES,
    MODULE_WITH_DEPENDENCIES_AND_LIBRARIES,
}

@Serializable
internal enum class ScopeProgramOp {
    PUSH_ATOM,
    AND,
    OR,
    NOT,
}

@Serializable
internal enum class ScopeShape {
    GLOBAL,
    LOCAL,
    MIXED,
}

@Serializable
internal data class ScopeAtomDto(
    val atomId: String,
    val kind: ScopeAtomKind,
    val scopeRefId: String? = null,
    val standardScopeId: String? = null,
    val moduleName: String? = null,
    val moduleFlavor: ModuleScopeFlavor? = null,
    val namedScopeName: String? = null,
    val namedScopeHolderId: String? = null,
    val patternText: String? = null,
    val directoryUrl: String? = null,
    val directoryWithSubdirectories: Boolean = true,
    val fileUrls: List<String> = emptyList(),
    val providerScopeId: String? = null,
)

@Serializable
internal data class ScopeProgramTokenDto(
    val op: ScopeProgramOp,
    val atomId: String? = null,
)

@Serializable
internal data class ScopeResolveRequestDto(
    val atoms: List<ScopeAtomDto>,
    val tokens: List<ScopeProgramTokenDto>,
    val strict: Boolean = true,
    val allowUiInteractiveScopes: Boolean = false,
)

@Serializable
internal data class ScopeCatalogItemDto(
    val scopeRefId: String,
    val displayName: String,
    val kind: ScopeAtomKind,
    val scopeShape: ScopeShape,
    val serializationId: String? = null,
    val requiresUserInput: Boolean = false,
    val unstable: Boolean = false,
    val moduleName: String? = null,
    val moduleFlavor: ModuleScopeFlavor? = null,
    val namedScopeName: String? = null,
    val namedScopeHolderId: String? = null,
    val providerScopeId: String? = null,
)

@Serializable
internal data class ScopeCatalogResultDto(
    val items: List<ScopeCatalogItemDto>,
)

@Serializable
internal data class ScopePatternValidationResultDto(
    val valid: Boolean,
    val normalizedPatternText: String? = null,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal data class ScopeResolveResultDto(
    val descriptor: ScopeProgramDescriptorDto,
)

@Serializable
internal data class ScopeDescribeProgramResultDto(
    val descriptor: ScopeProgramDescriptorDto,
)

@Serializable
internal data class ScopeProgramDescriptorDto(
    val version: Int = 1,
    val atoms: List<ScopeAtomDto>,
    val tokens: List<ScopeProgramTokenDto>,
    val displayName: String,
    val scopeShape: ScopeShape,
    val diagnostics: List<String> = emptyList(),
)
