/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.route.*
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.types.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Session-specific projection of resource routes into MCP list responses.
 *
 * The global primitive registry owns read dispatch. The catalog only answers
 * list requests by walking the immutable route snapshot and executing optional
 * per-segment list providers.
 */
internal class WorkspaceMcpResourceCatalog(
    private val projectResolver: WorkspaceProjectResolver,
) {
    private val snapshotRef = AtomicReference(ResourceRouteSnapshot())

    fun updateSnapshot(snapshot: ResourceRouteSnapshot) {
        snapshotRef.set(snapshot)
    }

    suspend fun listResources(connection: ClientConnection, request: ListResourcesRequest): ListResourcesResult {
        val snapshot = snapshotRef.get()
        val call = listCall(connection, request)
        return ListResourcesResult(
            resources = buildList {
                snapshot.routeRoots.values.forEach { root ->
                    addAll(root.traverse({ resourceDecision(call, it) }, root.name))
                }
            },
            nextCursor = null,
            meta = null,
        )
    }

    suspend fun listTemplates(connection: ClientConnection, request: ListResourceTemplatesRequest): ListResourceTemplatesResult {
        val snapshot = snapshotRef.get()
        val call = listCall(connection, request)
        return ListResourceTemplatesResult(
            resourceTemplates = buildList {
                snapshot.routeRoots.values.forEach { root ->
                    addAll(root.traverse({ templateDecision(call, it) }, root.name))
                }
            },
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
        )
    }

    // -- traversal --

    private suspend fun <T> ResourceSegment.traverse(
        decide: suspend ResourceSegment.(String) -> ResourceListDecision<T>,
        path: String,
    ): List<T> {
        val decision = decide(path)
        return if (decision.includeChildren) {
            decision.entries + childrenAndAnchors().flatMap { child ->
                child.traverse(decide, "$path/${child.name}")
            }
        } else {
            decision.entries
        }
    }

    // -- per-segment decisions (no child recursion) --

    private suspend fun ResourceSegment.resourceDecision(
        call: WorkspaceMcpCall<ListResourcesRequest>,
        path: String,
    ): ResourceListDecision<Resource> {
        val lp = resourceList?.listProvider
        if (lp != null) {
            val decision = McpCallContext(call).lp()
            if (decision.entries.isNotEmpty() || !decision.includeChildren) return decision
        }
        val ep = readEntries.firstOrNull()
        return if (ep == null || this is ParameterPathSegment) ResourceListDecision()
        else defaultResourceDecision(path, ep.mimeType)
    }

    private suspend fun ResourceSegment.templateDecision(
        call: WorkspaceMcpCall<ListResourceTemplatesRequest>,
        path: String,
    ): ResourceListDecision<ResourceTemplate> {
        val spec = templateList ?: return ResourceListDecision()
        return spec.listProvider?.invoke(McpCallContext(call))
            ?: defaultTemplateDecision(path, spec.mimeType)
    }

    private fun ResourceSegment.defaultResourceDecision(
        path: String,
        mimeType: String,
    ): ResourceListDecision<Resource> {
        return ResourceListDecision(
            entries = listOf(
                Resource(
                    uri = concreteInstanceUri(path),
                    name = name,
                    description = null,
                    mimeType = mimeType,
                ),
            ),
        )
    }

    private fun ResourceSegment.defaultTemplateDecision(
        path: String,
        mimeType: String,
    ): ResourceListDecision<ResourceTemplate> {
        return ResourceListDecision(
            entries = listOf(
                ResourceTemplate(
                    uriTemplate = concreteInstanceUri(path),
                    name = name,
                    description = null,
                    mimeType = mimeType,
                ),
            ),
        )
    }

    private fun ResourceSegment.childrenAndAnchors(): List<ResourceSegment> =
        children.values.toList()

    private fun concreteInstanceUri(path: String): String =
        "ij-workspace://${workspaceInstanceKey()}/$path"
}
