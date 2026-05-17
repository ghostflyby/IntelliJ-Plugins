/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.common

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
            throw WorkspaceResourceException(message)
        }
        BatchAttempt(value = null, error = message)
    }
}
