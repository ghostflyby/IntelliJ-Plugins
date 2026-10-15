/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import dev.ghostflyby.mcp.rest.markdown.TextBody
import kotlinx.serialization.Serializable

/**
 * Negotiated error payload: a `{"error":…}` object for JSON, the bare message for text types.
 *
 */
@Serializable
internal data class RestError(
    val error: String,
    val projectKey: String? = null,
) : TextBody {
    override fun renderTextBody(): String = error
}
