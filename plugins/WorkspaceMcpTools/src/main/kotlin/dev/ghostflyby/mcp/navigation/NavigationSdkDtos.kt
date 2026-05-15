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

import kotlinx.serialization.Serializable

@Serializable
internal data class NavigationResult(
    val targetVirtualFileUri: String,
    val row: Int,
    val column: Int,
)

@Serializable
internal data class NavigationResults(
    val items: List<NavigationResult>,
    val diagnostics: List<String> = emptyList(),
)

@Serializable
internal data class NavigationSourcePosition(
    val uri: String,
    val row: Int,
    val column: Int,
)

@Serializable
internal data class NavigationBatchSingleItem(
    val input: NavigationSourcePosition,
    val result: NavigationResult? = null,
    val error: String? = null,
)

@Serializable
internal data class NavigationBatchMultiItem(
    val input: NavigationSourcePosition,
    val result: NavigationResults? = null,
    val error: String? = null,
)

@Serializable
internal data class NavigationBatchSingleResult(
    val items: List<NavigationBatchSingleItem>,
    val successCount: Int,
    val failureCount: Int,
)

@Serializable
internal data class NavigationBatchMultiResult(
    val items: List<NavigationBatchMultiItem>,
    val successCount: Int,
    val failureCount: Int,
)

@Serializable
internal data class NavigationSymbolInfoResult(
    val documentation: String,
)

@Serializable
internal data class NavigationSymbolInfoPosition(
    val uri: String,
    val row: Int,
    val column: Int,
)

@Serializable
internal data class NavigationSymbolInfoAutoPositionInput(
    val row: Int? = null,
    val column: Int? = null,
    val offset: Int? = null,
)

@Serializable
internal data class NavigationSymbolInfoResolvedResult(
    val documentation: String,
    val row: Int,
    val column: Int,
    val offset: Int,
    val recommendedNextCalls: List<String> = emptyList(),
)

@Serializable
internal data class NavigationBatchSymbolInfoItem(
    val input: NavigationSymbolInfoPosition,
    val result: NavigationSymbolInfoResult? = null,
    val error: String? = null,
)

@Serializable
internal data class NavigationBatchSymbolInfoResult(
    val items: List<NavigationBatchSymbolInfoItem>,
    val successCount: Int,
    val failureCount: Int,
)
