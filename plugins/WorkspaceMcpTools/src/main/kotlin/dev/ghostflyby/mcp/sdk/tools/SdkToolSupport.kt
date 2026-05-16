/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk.tools

import kotlinx.serialization.json.Json

internal interface WorkspaceMcpProjectToolArguments {
    val projectKey: String?
    val projectPath: String?
}

internal val toolArgsJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
