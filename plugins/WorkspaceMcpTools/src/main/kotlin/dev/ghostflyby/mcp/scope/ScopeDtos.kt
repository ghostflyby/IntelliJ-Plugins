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

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

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
    @OptIn(ExperimentalSerializationApi::class)
    @JsonNames("ATOM")
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
internal enum class ScopeAtomFailureMode {
    FAIL,
    EMPTY_SCOPE,
    SKIP,
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
    val onResolveFailure: ScopeAtomFailureMode? = null,
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
    val nonStrictDefaultFailureMode: ScopeAtomFailureMode = ScopeAtomFailureMode.EMPTY_SCOPE,
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
    val diagnostics: List<String> = emptyList(),
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
    val version: Int = 2,
    val atoms: List<ScopeAtomDto>,
    val tokens: List<ScopeProgramTokenDto>,
    val displayName: String,
    val scopeShape: ScopeShape,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal data class ScopeContainsFileResultDto(
    val fileUrl: String,
    val matches: Boolean,
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal data class ScopeFilterFilesResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val matchedFileUrls: List<String>,
    val excludedFileUrls: List<String>,
    val missingFileUrls: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal enum class ScopeFileSearchMode {
    NAME,
    PATH,
    NAME_OR_PATH,
    GLOB,
}

@Serializable
internal data class ScopeFileSearchResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val mode: ScopeFileSearchMode,
    val query: String,
    val keywords: List<String> = emptyList(),
    val directoryUrl: String? = null,
    val matchedFileUrls: List<String>,
    val scannedFileCount: Int,
    val probablyHasMoreMatchingFiles: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal enum class ScopeTextQueryMode {
    PLAIN,
    REGEX,
}

@Serializable
internal enum class ScopeSymbolKind {
    CLASS,
    METHOD,
    FIELD,
    SYMBOL,
    UNKNOWN,
}

@Serializable
internal data class ScopeSymbolSearchItemDto(
    val name: String,
    val qualifiedName: String? = null,
    val fileUrl: String? = null,
    val filePath: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val kind: ScopeSymbolKind = ScopeSymbolKind.UNKNOWN,
    val language: String? = null,
    val score: Int? = null,
)

@Serializable
internal data class ScopeSymbolSearchResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val query: String,
    val includeNonProjectItems: Boolean,
    val requirePhysicalLocation: Boolean,
    val items: List<ScopeSymbolSearchItemDto>,
    val probablyHasMoreMatchingEntries: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal enum class ScopeTextSearchContextDto {
    ANY,
    IN_STRING_LITERALS,
    IN_COMMENTS,
    EXCEPT_STRING_LITERALS,
    EXCEPT_COMMENTS,
    EXCEPT_COMMENTS_AND_STRING_LITERALS,
}

@Serializable
internal data class ScopeTextSearchRequestDto(
    val query: String,
    val mode: ScopeTextQueryMode = ScopeTextQueryMode.PLAIN,
    val caseSensitive: Boolean = true,
    val wholeWordsOnly: Boolean = false,
    val searchContext: ScopeTextSearchContextDto = ScopeTextSearchContextDto.ANY,
    val fileMask: String? = null,
    val scope: ScopeProgramDescriptorDto,
    val allowUiInteractiveScopes: Boolean = false,
    val maxUsageCount: Int = 1000,
    val timeoutMillis: Int = 30000,
    val allowEmptyMatches: Boolean = false,
)

@Serializable
internal data class ScopeTextOccurrenceDto(
    val occurrenceId: String,
    val fileUrl: String,
    val filePath: String,
    val lineNumber: Int,
    val startOffset: Int,
    val endOffset: Int,
    val lineText: String,
    val matchedText: String,
)

@Serializable
internal data class ScopeTextSearchResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val mode: ScopeTextQueryMode,
    val query: String,
    val caseSensitive: Boolean,
    val wholeWordsOnly: Boolean,
    val searchContext: ScopeTextSearchContextDto,
    val fileMask: String? = null,
    val occurrences: List<ScopeTextOccurrenceDto>,
    val probablyHasMoreMatchingEntries: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal data class ScopeTextReplaceRequestDto(
    val search: ScopeTextSearchRequestDto,
    val replaceWith: String,
    val preserveCase: Boolean = false,
    val occurrenceIds: List<String> = emptyList(),
    val failOnMissingOccurrenceIds: Boolean = true,
    val saveAfterWrite: Boolean = true,
    val maxReplaceCount: Int = 10000,
)

@Serializable
internal data class ScopeTextReplacementPreviewEntryDto(
    val occurrence: ScopeTextOccurrenceDto,
    val replacementText: String,
)

@Serializable
internal data class ScopeTextReplacePreviewResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val query: String,
    val mode: ScopeTextQueryMode,
    val replaceWith: String,
    val selectedEntries: List<ScopeTextReplacementPreviewEntryDto>,
    val missingOccurrenceIds: List<String> = emptyList(),
    val probablyHasMoreMatchingEntries: Boolean = false,
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal data class ScopeTextReplaceApplyResultDto(
    val scopeDisplayName: String,
    val scopeShape: ScopeShape,
    val query: String,
    val mode: ScopeTextQueryMode,
    val replaceWith: String,
    val requestedOccurrenceCount: Int,
    val replacedOccurrenceCount: Int,
    val replacedFileCount: Int,
    val replacedOccurrenceIds: List<String>,
    val missingOccurrenceIds: List<String> = emptyList(),
    val timedOut: Boolean = false,
    val canceled: Boolean = false,
    val diagnostics: List<String> = emptyList(),
)
