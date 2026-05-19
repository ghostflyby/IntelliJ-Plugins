/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf

internal typealias ResourceReadHandler = suspend (WorkspaceMcpCall<ReadResourceRequest>) -> ReadResourceResult
internal typealias ResourceListProvider<R, T> = suspend WorkspaceMcpCall<R>.() -> ResourceListDecision<T>

internal typealias ConcreteResourceListProvider = ResourceListProvider<ListResourcesRequest, Resource>
internal typealias TemplateResourceListProvider = ResourceListProvider<ListResourceTemplatesRequest, ResourceTemplate>

internal data class ResourceListDecision<T>(
    val entries: List<T> = emptyList(),
    val includeChildren: Boolean = true,
)

internal data class WorkspaceMcpListProject(
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

internal sealed class ResourceSegment {
    abstract val name: String
    abstract val extensible: Boolean
    var ownerFeatureName: String? = null
    val resourceEndpoints: MutableList<ResourceEndpointEntry> = mutableListOf()
    var templateEndpoint: ResourceTemplateEndpoint? = null
    var routePattern: RoutePattern? = null
    var routeAnchor: RouteAnchor? = null

    var children: PersistentMap<String, ResourceSegment> = persistentHashMapOf()
    var attachedSegments: PersistentList<ResourceSegment> = persistentListOf()
}

internal data class ResourceEndpoint(
    val handler: ResourceReadHandler,
    val listProvider: ConcreteResourceListProvider? = null,
    val description: String = "",
    val mimeType: String = "application/json",
)

internal data class ResourceEndpointEntry(
    val endpoint: ResourceEndpoint,
    val queryTokens: List<QueryToken> = emptyList(),
)

internal val ResourceEndpointEntry.queryTemplate: String
    get() {
        if (queryTokens.isEmpty()) return ""
        return queryTokens.joinToString("&", prefix = "?") { token ->
            when {
                token.paramName != null -> "${token.key}={${token.paramName}}"
                token.literalValue != null -> "${token.key}=${token.literalValue}"
                else -> token.key
            }
        }
    }

internal data class ResourceTemplateEndpoint(
    val listProvider: TemplateResourceListProvider? = null,
    val description: String = "",
    val mimeType: String = "text/plain",
)

internal class LiteralPathSegment(
    override val name: String,
    override val extensible: Boolean = false,
) : ResourceSegment()

internal class ParameterPathSegment(
    override val name: String,
    val paramName: String,
    override val extensible: Boolean = false,
) : ResourceSegment()
