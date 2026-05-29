/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.tools

import dev.ghostflyby.mcp.server.McpCallFactory
import dev.ghostflyby.mcp.server.mcpCallFactory
import dev.ghostflyby.mcp.server.route.McpCallContext
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.Request

internal val TestCallFactory: McpCallFactory = mcpCallFactory()
