/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.resource.WorkspaceListableResource
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult

/**
 * SDK-neutral feature registration boundary for the Workspace MCP SDK server.
 *
 * Each [WorkspaceMcpFeature] owns a domain (e.g. core metadata, VFS resources,
 * document resources) and is responsible for:
 * - computing its own listable resources on demand,
 * - registering resource templates and SDK tools during server startup.
 *
 * The server service aggregates features and delegates resource listing,
 * template registration, and tool registration to them.
 */
internal interface WorkspaceMcpFeature {
    /** Human-readable name for diagnostics and error messages. */
    val featureName: String

    /**
     * Compute the current set of listable resources for this feature.
     * Called on initial server creation and on resource list change events
     * (e.g. project open/close, file open/close, module root changes).
     */
    suspend fun computeListableResources(): List<WorkspaceListableResource>

    /**
     * One-time registration of resource templates and MCP SDK tools
     * on the given [server]. The [readResource] callback is provided
     * by the server service for handling template-resource reads
     * with full context installation and project resolution.
     */
    fun registerOnServer(
        server: Server,
        readResource: suspend (resourceUri: String, sessionId: String?) -> ReadResourceResult,
    )
}
