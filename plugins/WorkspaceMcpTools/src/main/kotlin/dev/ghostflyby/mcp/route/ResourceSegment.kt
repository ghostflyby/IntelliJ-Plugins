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

internal data class WorkspaceMcpListProject(
    val projectKey: String,
    val name: String,
    val basePath: String?,
)

internal sealed class ResourceSegment {
    abstract val name: String
    abstract val extensible: Boolean
    var ownerFeatureName: String? = null

    var children: PersistentMap<String, ResourceSegment> = persistentHashMapOf()

    /** Read routes, each optionally bound to query parameters. */
    var readRoutes: PersistentList<ResourceReadRoute> = persistentListOf()
}

internal data class ResourceReadRoute(
    val description: String = "",
    val mimeType: String = "application/json",
    val resourceClassInfo: ResourceClassInfo? = null,
    val invoker: suspend (McpCallContext<ReadResourceRequest>, Any?) -> ReadResourceResult,
    val paramDeserializer: ((Map<String, String>) -> Any?)? = null,
)

internal data class ResourceListRoute(
    val listKey: String,
    val resourceClassInfo: ResourceClassInfo? = null,
    val provider: suspend McpCallContext<ListResourcesRequest>.() -> List<Resource>,
)

internal data class ResourceTemplateListRoute(
    val listKey: String,
    val resourceClassInfo: ResourceClassInfo? = null,
    val provider: suspend McpCallContext<ListResourceTemplatesRequest>.() -> List<ResourceTemplate>,
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
