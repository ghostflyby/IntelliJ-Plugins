/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceRequest
import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import java.util.concurrent.atomic.AtomicReference

public data class ResourceRouteResource(
    public val uri: String,
    public val name: String,
    public val description: String,
    public val mimeType: String,
    public val ownerFeatureName: String,
    public val invoker: suspend (McpCallContext<ReadResourceRequest>, Any?) -> ReadResourceResult,
    public val readRoute: ResourceReadRoute,
    public val isParameterized: Boolean,
)

/** A read route's default list projection. Concrete routes produce [Resource],
 * parameterized routes produce [ResourceTemplate]. Never both. */
public sealed interface ReadRouteDefault {
    public data class ResourceEntry(val resource: Resource) : ReadRouteDefault
    public data class TemplateEntry(val template: ResourceTemplate) : ReadRouteDefault
}

public data class ResourceRouteSnapshot(
    public val resources: List<ResourceRouteResource> = emptyList(),
    public val defaultFallbacks: List<ReadRouteDefault> = emptyList(),
    public val resourceListRoutes: List<ResourceListRoute> = emptyList(),
    public val templateListRoutes: List<ResourceTemplateListRoute> = emptyList(),
    public val routeRoots: Map<String, ResourceSegment> = emptyMap(),
    private val parameterizedResourceByUri: Map<String, ResourceRouteResource> = emptyMap(),
) {
    public fun parameterizedResource(uriTemplate: String): ResourceRouteResource? =
        parameterizedResourceByUri[uriTemplate]

    private val format = WorkspaceResourceUriFormat()

    public fun matchUri(uri: String): ResourceRouteMatch? {
        for (resource in resources) {
            val info = resource.readRoute.resourceClassInfo ?: continue
            val params = format.tryMatch(uri, info) ?: continue
            return ResourceRouteMatch(resource = resource, params = AncestorContext(params))
        }
        return null
    }
}

public data class ResourceRouteMatch(
    public val resource: ResourceRouteResource,
    public val params: AncestorContext,
)

public class ResourceRouteSnapshotRef(
    initialSnapshot: ResourceRouteSnapshot = ResourceRouteSnapshot(),
) {
    private val ref = AtomicReference(initialSnapshot)

    public fun get(): ResourceRouteSnapshot = ref.get()
    public fun set(snapshot: ResourceRouteSnapshot) {
        ref.set(snapshot)
    }
}

public class SegmentTreeTemplateMatcher(
    override val resourceTemplate: ResourceTemplate,
    private val snapshotRef: ResourceRouteSnapshotRef,
) : ResourceTemplateMatcher {
    override fun match(resourceUri: String): MatchResult? {
        val snapshot = snapshotRef.get()
        val entry = snapshot.parameterizedResource(resourceTemplate.uriTemplate) ?: return null
        val routeMatch = snapshot.matchUri(resourceUri) ?: return null
        if (routeMatch.resource.invoker !== entry.invoker) return null
        return MatchResult(variables = routeMatch.params, score = 100)
    }
}
