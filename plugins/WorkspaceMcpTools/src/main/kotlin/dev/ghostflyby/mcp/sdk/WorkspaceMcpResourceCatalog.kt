/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.route.AncestorContext
import dev.ghostflyby.mcp.route.ParameterPathSegment
import dev.ghostflyby.mcp.route.ResourceListDecision
import dev.ghostflyby.mcp.route.ResourceRouteSnapshot
import dev.ghostflyby.mcp.route.ResourceSegment
import dev.ghostflyby.mcp.route.WorkspaceMcpCall
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourceTemplatesResult
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListResourcesResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate

/**
 * Session-specific projection of resource routes into MCP list responses.
 *
 * The global primitive registry owns read dispatch. The catalog only answers
 * list requests by walking the immutable route snapshot and executing optional
 * per-segment list providers.
 */
internal class WorkspaceMcpResourceCatalog(
    private val sessionState: WorkspaceMcpSessionState,
    private val projectResolver: WorkspaceProjectResolver,
) {
    private val lock = Any()
    private var currentSnapshot: ResourceRouteSnapshot = ResourceRouteSnapshot()
    private var currentVersion: Long = 0L

    fun updateSnapshot(snapshot: ResourceRouteSnapshot) {
        synchronized(lock) {
            currentSnapshot = snapshot
            currentVersion++
        }
    }

    suspend fun listResources(sessionId: String, request: ListResourcesRequest): ListResourcesResult {
        val snapshot = synchronized(lock) { currentSnapshot }
        val call = listCall(sessionId, request)
        return ListResourcesResult(
            resources = buildList {
                snapshot.routeRoots.values.forEach { root ->
                    addAll(root.listResources(call, path = root.name, hasParameter = root is ParameterPathSegment))
                }
            },
            nextCursor = null,
            meta = null,
        )
    }

    suspend fun listTemplates(
        sessionId: String,
        request: ListResourceTemplatesRequest,
    ): ListResourceTemplatesResult {
        val snapshot = synchronized(lock) { currentSnapshot }
        val call = listCall(sessionId, request)
        return ListResourceTemplatesResult(
            resourceTemplates = buildList {
                snapshot.routeRoots.values.forEach { root ->
                    addAll(root.listTemplates(call, path = root.name))
                }
            },
            nextCursor = null,
            meta = null,
        )
    }

    private fun <R : io.modelcontextprotocol.kotlin.sdk.types.Request> listCall(
        sessionId: String,
        request: R,
    ): WorkspaceMcpCall<R> {
        return WorkspaceMcpCall(
            connection = null,
            request = request,
            ancestors = AncestorContext(emptyMap()),
            sessionState = sessionState,
            sessionIdOverride = sessionId,
            projectResolver = projectResolver,
        )
    }

    private suspend fun ResourceSegment.listResources(
        call: WorkspaceMcpCall<ListResourcesRequest>,
        path: String,
        hasParameter: Boolean,
    ): List<Resource> {
        val endpoint = resourceEndpoint
        val decision = if (endpoint != null) {
            endpoint.listProvider?.let { provider ->
                call.provider()
            } ?: if (hasParameter) {
                ResourceListDecision()
            } else {
                defaultResourceDecision(path, endpoint.mimeType)
            }
        } else {
            ResourceListDecision()
        }
        if (!decision.includeChildren) return decision.entries
        return decision.entries + childrenAndAnchors().flatMap { child ->
            child.listResources(
                call = call,
                path = "$path/${child.name}",
                hasParameter = hasParameter || child is ParameterPathSegment,
            )
        }
    }

    private suspend fun ResourceSegment.listTemplates(
        call: WorkspaceMcpCall<ListResourceTemplatesRequest>,
        path: String,
    ): List<ResourceTemplate> {
        val endpoint = templateEndpoint
        val decision = if (endpoint != null) {
            endpoint.listProvider?.let { provider ->
                call.provider()
            } ?: defaultTemplateDecision(path, endpoint.mimeType)
        } else {
            ResourceListDecision()
        }
        if (!decision.includeChildren) return decision.entries
        return decision.entries + childrenAndAnchors().flatMap { child ->
            child.listTemplates(call, "$path/${child.name}")
        }
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

    private fun ResourceSegment.childrenAndAnchors(): List<ResourceSegment> {
        return children.values.toList() + anchors.values
    }

    private fun concreteInstanceUri(path: String): String =
        "ij-workspace://${workspaceInstanceKey()}/$path"
}
