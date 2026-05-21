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

package dev.ghostflyby.mcp.navigation

import kotlinx.schema.Description
import kotlinx.schema.Schema
import kotlinx.serialization.Serializable

@Schema
@Serializable
internal data class NavigationResult(
    @Description("The VFS URL of the declared target file.")
    val targetVirtualFileUri: String,
    @Description("1-based line number of the target location.")
    val row: Int,
    @Description("1-based column number of the target location.")
    val column: Int,
)

@Schema
@Serializable
internal data class NavigationResults(
    @Description("List of navigation results.")
    val items: List<NavigationResult>,
    @Description("Diagnostic messages about the search.")
    val diagnostics: List<String> = emptyList(),
)

@Schema
@Serializable
internal data class NavigationSourcePosition(
    @Description("VFS URL of the source file.")
    val uri: String,
    @Description("1-based line number.")
    val row: Int,
    @Description("1-based column number.")
    val column: Int,
)

@Schema
@Serializable
internal data class NavigationBatchSingleItem(
    @Description("The source position that was resolved.")
    val input: NavigationSourcePosition,
    @Description("Resolved navigation result, null on error.")
    val result: NavigationResult? = null,
    @Description("Error message if resolution failed.")
    val error: String? = null,
)

@Schema
@Serializable
internal data class NavigationBatchMultiItem(
    @Description("The source position that was resolved.")
    val input: NavigationSourcePosition,
    @Description("Navigation results (possibly multiple items), null on error.")
    val result: NavigationResults? = null,
    @Description("Error message if resolution failed.")
    val error: String? = null,
)

@Schema
@Serializable
internal data class NavigationBatchSingleResult(
    @Description("Individual batch item results.")
    val items: List<NavigationBatchSingleItem>,
    @Description("Number of successfully resolved items.")
    val successCount: Int,
    @Description("Number of items that failed to resolve.")
    val failureCount: Int,
)

@Schema
@Serializable
internal data class NavigationBatchMultiResult(
    @Description("Individual batch item results.")
    val items: List<NavigationBatchMultiItem>,
    @Description("Number of successfully resolved items.")
    val successCount: Int,
    @Description("Number of items that failed to resolve.")
    val failureCount: Int,
)

@Schema
@Serializable
internal data class NavigationSymbolInfoResult(
    @Description("Documentation text, may be empty.")
    val documentation: String,
)

@Schema
@Serializable
internal data class NavigationSymbolInfoPosition(
    @Description("VFS URL of the source file.")
    val uri: String,
    @Description("1-based line number.")
    val row: Int,
    @Description("1-based column number.")
    val column: Int,
)

@Schema
@Serializable
internal data class NavigationSymbolInfoResolvedResult(
    @Description("Documentation text, may be empty.")
    val documentation: String,
    @Description("Resolved 1-based line number.")
    val row: Int,
    @Description("Resolved 1-based column number.")
    val column: Int,
    @Description("Resolved 0-based offset.")
    val offset: Int,
    @Description("Recommended next tool calls after this result.")
    val recommendedNextCalls: List<String> = emptyList(),
)

@Schema
@Serializable
internal data class NavigationBatchSymbolInfoItem(
    @Description("The source position that was resolved.")
    val input: NavigationSymbolInfoPosition,
    @Description("Symbol info result, null on error.")
    val result: NavigationSymbolInfoResult? = null,
    @Description("Error message if resolution failed.")
    val error: String? = null,
)

@Schema
@Serializable
internal data class NavigationBatchSymbolInfoResult(
    @Description("Individual batch item results.")
    val items: List<NavigationBatchSymbolInfoItem>,
    @Description("Number of successfully resolved items.")
    val successCount: Int,
    @Description("Number of items that failed to resolve.")
    val failureCount: Int,
)
