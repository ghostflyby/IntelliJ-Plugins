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

package dev.ghostflyby.mcp.common

import com.intellij.mcpserver.mcpFail

internal data class BatchAttempt<T>(
    val value: T?,
    val error: String?,
)

internal suspend fun <T> batchTry(
    continueOnError: Boolean,
    action: suspend () -> T,
): BatchAttempt<T> {
    return try {
        BatchAttempt(value = action(), error = null)
    } catch (error: Throwable) {
        if (error is java.util.concurrent.CancellationException) throw error
        val message = error.message ?: error::class.java.simpleName
        if (!continueOnError) {
            mcpFail(message)
        }
        BatchAttempt(value = null, error = message)
    }
}
