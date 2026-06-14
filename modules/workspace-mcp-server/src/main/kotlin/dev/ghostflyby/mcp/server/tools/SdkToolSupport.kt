/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import kotlinx.serialization.json.Json

public interface WorkspaceMcpProjectToolArguments {
    public val projectKey: String?
    public val projectPath: String?
}

public val toolArgsJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}
