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

@JvmInline
internal value class McpCallContext<R : Request>(val call: WorkspaceMcpCall<R>)

internal data class ResourceListDecision<T>(
    val entries: List<T> = emptyList(),
    val includeChildren: Boolean = true,
)

internal data class WorkspaceMcpListProject(
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

// -- Segment tree node --

internal sealed class ResourceSegment {
    abstract val name: String
    abstract val extensible: Boolean
    var ownerFeatureName: String? = null

    var children: PersistentMap<String, ResourceSegment> = persistentHashMapOf()

    /** Read routes, each optionally bound to query parameters. */
    var readRoutes: PersistentList<ResourceReadRoute> = persistentListOf()

    /** Behaviour for resources/list at this segment. Null means no resource listing. */
    var resourceListRoute: ResourceListRoute? = null

    /** Behaviour for resources/templates/list at this segment. Null means no template listing. */
    var templateListRoute: ResourceTemplateListRoute? = null
}

internal data class ResourceReadRoute(
    val description: String = "",
    val mimeType: String = "application/json",
    val resourceClassInfo: ResourceClassInfo? = null,
    val invoker: suspend (McpCallContext<ReadResourceRequest>, Any?) -> ReadResourceResult,
    val paramDeserializer: ((Map<String, String>) -> Any?)? = null,
)

internal data class ResourceListRoute(
    val description: String = "",
    val mimeType: String = "application/json",
    val resourceClassInfo: ResourceClassInfo? = null,
    val provider: (suspend McpCallContext<ListResourcesRequest>.() -> ResourceListDecision<Resource>)? = null,
)

internal data class ResourceTemplateListRoute(
    val description: String = "",
    val mimeType: String = "text/plain",
    val resourceClassInfo: ResourceClassInfo? = null,
    val provider: (suspend McpCallContext<ListResourceTemplatesRequest>.() -> ResourceListDecision<ResourceTemplate>)? = null,
)

// -- Segment subtypes --

internal class LiteralPathSegment(
    override val name: String,
    override val extensible: Boolean = false,
) : ResourceSegment()

internal class ParameterPathSegment(
    override val name: String,
    val paramName: String,
    override val extensible: Boolean = false,
) : ResourceSegment()
