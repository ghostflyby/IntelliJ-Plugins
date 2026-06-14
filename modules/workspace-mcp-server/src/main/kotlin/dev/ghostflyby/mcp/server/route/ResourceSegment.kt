/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf

@JvmInline
public value class McpCallContext<R : Request>(public val call: WorkspaceMcpCall<R>)

public data class WorkspaceMcpListProject(
    public val projectKey: String,
    public val name: String,
    public val basePath: String?,
)

public sealed class ResourceSegment {
    public abstract val name: String
    public abstract val extensible: Boolean
    public var ownerFeatureName: String? = null

    public var children: PersistentMap<String, ResourceSegment> = persistentHashMapOf()

    /** Read routes, each optionally bound to query parameters. */
    public var readRoutes: PersistentList<ResourceReadRoute> = persistentListOf()
}

public data class ResourceReadRoute(
    public val description: String = "",
    public val mimeType: String = "application/json",
    public val resourceClassInfo: ResourceClassInfo? = null,
    public val invoker: suspend (McpCallContext<ReadResourceRequest>, Any?) -> ReadResourceResult,
    public val paramDeserializer: ((Map<String, String>) -> Any?)? = null,
)

public data class ResourceListRoute(
    public val listKey: String,
    public val resourceClassInfo: ResourceClassInfo? = null,
    public val provider: suspend McpCallContext<ListResourcesRequest>.() -> List<Resource>,
)

public data class ResourceTemplateListRoute(
    public val listKey: String,
    public val resourceClassInfo: ResourceClassInfo? = null,
    public val provider: suspend McpCallContext<ListResourceTemplatesRequest>.() -> List<ResourceTemplate>,
)

public class LiteralPathSegment(
    override val name: String,
    override val extensible: Boolean = false,
) : ResourceSegment()

public class ParameterPathSegment(
    override val name: String,
    public val paramName: String,
    override val extensible: Boolean = false,
) : ResourceSegment()
