/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.common

/**
 * Exception thrown when a workspace MCP resource operation fails.
 * Used across tools (document, navigation, batch, etc.) for consistent error reporting.
 */
internal class WorkspaceResourceException(message: String) : RuntimeException(message)
