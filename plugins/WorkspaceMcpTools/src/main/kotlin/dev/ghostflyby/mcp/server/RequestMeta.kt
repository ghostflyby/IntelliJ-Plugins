/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server

import io.modelcontextprotocol.kotlin.sdk.types.Request
import kotlinx.serialization.json.JsonPrimitive

internal val Request.progressToken: String?
    get() = (params?.meta?.get("progressToken") as? JsonPrimitive)?.content

