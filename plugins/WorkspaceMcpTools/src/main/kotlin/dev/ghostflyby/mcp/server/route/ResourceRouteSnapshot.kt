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

internal data class ResourceRouteResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val ownerFeatureName: String,
    val invoker: suspend (McpCallContext<ReadResourceRequest>, Any?) -> ReadResourceResult,
    val readRoute: ResourceReadRoute,
    val isParameterized: Boolean,
)

/** A read route's default list projection. Concrete routes produce [Resource],
 * parameterized routes produce [ResourceTemplate]. Never both. */
internal sealed interface ReadRouteDefault {
    data class ResourceEntry(val resource: Resource) : ReadRouteDefault
    data class TemplateEntry(val template: ResourceTemplate) : ReadRouteDefault
}

internal data class ResourceRouteSnapshot(
    val resources: List<ResourceRouteResource> = emptyList(),
    val defaultFallbacks: List<ReadRouteDefault> = emptyList(),
    val resourceListRoutes: List<ResourceListRoute> = emptyList(),
    val templateListRoutes: List<ResourceTemplateListRoute> = emptyList(),
    val routeRoots: Map<String, ResourceSegment> = emptyMap(),
    private val parameterizedResourceByUri: Map<String, ResourceRouteResource> = emptyMap(),
) {
    fun parameterizedResource(uriTemplate: String): ResourceRouteResource? =
        parameterizedResourceByUri[uriTemplate]

    private val format = WorkspaceResourceUriFormat()

    fun matchUri(uri: String): ResourceRouteMatch? {
        for (resource in resources) {
            val info = resource.readRoute.resourceClassInfo ?: continue
            val params = format.tryMatch(uri, info) ?: continue
            return ResourceRouteMatch(resource = resource, params = AncestorContext(params))
        }
        return null
    }
}

internal data class ResourceRouteMatch(
    val resource: ResourceRouteResource,
    val params: AncestorContext,
)

internal class ResourceRouteSnapshotRef(
    initialSnapshot: ResourceRouteSnapshot = ResourceRouteSnapshot(),
) {
    private val ref = AtomicReference(initialSnapshot)

    fun get(): ResourceRouteSnapshot = ref.get()
    fun set(snapshot: ResourceRouteSnapshot) {
        ref.set(snapshot)
    }
}

internal class SegmentTreeTemplateMatcher(
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
