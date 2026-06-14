/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.extensions.ExtensionPointName.Companion.create
import dev.ghostflyby.mcp.server.WorkspaceMcpFeature

internal val WORKSPACE_MCP_FEATURE_EP: ExtensionPointName<WorkspaceMcpFeature> =
    create("dev.ghostflyby.mcp.workspace.workspaceFeature")

