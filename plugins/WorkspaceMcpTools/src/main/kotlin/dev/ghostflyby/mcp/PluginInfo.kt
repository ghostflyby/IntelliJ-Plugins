/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.extensions.PluginId

private val LOG = Logger.getInstance("dev.ghostflyby.mcp.PluginInfo")
private val WORKSPACE_MCP_PLUGIN_ID = PluginId.getId("dev.ghostflyby.mcp.workspace")

internal val pluginVersion: String
    get() = PluginManagerCore.getPlugin(WORKSPACE_MCP_PLUGIN_ID)?.version
        ?: "unknown".also {
            LOG.warn("Plugin descriptor was not found for $WORKSPACE_MCP_PLUGIN_ID; reporting version as unknown.")
        }
