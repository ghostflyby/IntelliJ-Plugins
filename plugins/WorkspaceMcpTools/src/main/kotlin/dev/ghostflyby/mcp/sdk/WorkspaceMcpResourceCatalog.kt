/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.service
import dev.ghostflyby.mcp.route.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Session-specific projection of resource routes into MCP list responses.
 *
 * The global primitive registry owns read dispatch. The catalog only answers
 * list requests by combining read-route fallback projections and explicit
 * flat list providers.
 */
internal class WorkspaceMcpResourceCatalog(
    private val instanceKeyProvider: () -> String = ::workspaceInstanceKey,
) {
    private val snapshotRef = AtomicReference(ResourceRouteSnapshot())

    fun updateSnapshot(snapshot: ResourceRouteSnapshot) {
        snapshotRef.set(snapshot)
    }

    suspend fun listResources(connection: ClientConnection, request: ListResourcesRequest): ListResourcesResult {
        val snapshot = snapshotRef.get()
        val call = listCall(connection, request)
        return ListResourcesResult(
            resources = distinctResources(
                snapshot.defaultFallbacks.flatMap { fb ->
                    if (fb is ReadRouteDefault.ResourceEntry)
                        listOf(fb.resource.copy(uri = fb.resource.uri.replace("{instanceKey}", instanceKeyProvider())))
                    else emptyList()
                } +
                        snapshot.resourceListRoutes.flatMap { route ->
                            route.provider(McpCallContext(call))
                        },
            ),
            nextCursor = null,
            meta = null,
        )
    }

    suspend fun listTemplates(connection: ClientConnection, request: ListResourceTemplatesRequest): ListResourceTemplatesResult {
        val snapshot = snapshotRef.get()
        val call = listCall(connection, request)
        return ListResourceTemplatesResult(
            resourceTemplates = distinctTemplates(
                snapshot.defaultFallbacks.flatMap { fb ->
                    if (fb is ReadRouteDefault.TemplateEntry) listOf(fb.template) else emptyList()
                } +
                        snapshot.templateListRoutes.flatMap { route ->
                            route.provider(McpCallContext(call))
                        },
            ),
            nextCursor = null,
            meta = null,
        )
    }

    private fun <R : Request> listCall(
        connection: ClientConnection,
        request: R,
    ): WorkspaceMcpCall<R> {
        return WorkspaceMcpCall(
            connection = connection,
            request = request,
            parameters = AncestorContext(emptyMap()),
            projectResolver = service<WorkspaceProjectResolver>(),
        )
    }

    private fun distinctResources(resources: List<Resource>): List<Resource> {
        val seen = linkedSetOf<String>()
        return resources.filter { seen.add(it.uri) }
    }

    private fun distinctTemplates(templates: List<ResourceTemplate>): List<ResourceTemplate> {
        val seen = linkedSetOf<String>()
        return templates.filter { seen.add(it.uriTemplate) }
    }
}
