/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

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
    val handler: ResourceReadHandler,
)

internal data class ResourceRouteTemplate(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val ownerFeatureName: String,
    val handler: ResourceReadHandler,
    val segmentId: SegmentId,
    val paramToSegmentId: Map<String, SegmentId>,
)

internal data class ResourceRouteSnapshot(
    val resources: List<ResourceRouteResource> = emptyList(),
    val templates: List<ResourceRouteTemplate> = emptyList(),
    private val roots: Map<String, ResourceSegment> = emptyMap(),
    private val templateUriBySegmentId: Map<SegmentId, String> = emptyMap(),
) {
    fun templateUri(segmentId: SegmentId): String? = templateUriBySegmentId[segmentId]

    fun segmentMatch(uri: String): ResourceRouteMatch? {
        val parsed = parseWorkspaceUri(uri) ?: return null
        val parts = parsed.path.removePrefix("/").split("/")
        val root = roots[parts.firstOrNull()] ?: return null
        return matchSegment(
            segment = root,
            parts = parts,
            index = 1,
            params = mapOf("instanceKey" to parsed.instanceKey),
            segmentIndex = emptyList(),
        )
    }

    private fun parseWorkspaceUri(uri: String): ParsedWorkspaceUri? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = uri.substring(schemeEnd + 3)
        val firstSlash = afterScheme.indexOf('/')
        if (firstSlash < 0) return null
        val instanceKey = afterScheme.substring(0, firstSlash)
        if (instanceKey.isBlank()) return null
        return ParsedWorkspaceUri(
            instanceKey = instanceKey,
            path = afterScheme.substring(firstSlash),
        )
    }

    private fun matchSegment(
        segment: ResourceSegment,
        parts: List<String>,
        index: Int,
        params: Map<String, String>,
        segmentIndex: List<Pair<SegmentId, String>>,
    ): ResourceRouteMatch? {
        when (segment) {
            is StaticSegment -> {
                if (index >= parts.size) {
                    return if (segment.children.isEmpty() && segment.anchors.isEmpty()) {
                        ResourceRouteMatch(segment, AncestorContext(params, segmentIndex.toMap()))
                    } else {
                        null
                    }
                }
                val nextPart = parts[index]
                segment.children[nextPart]?.let { child ->
                    matchSegment(child, parts, index + 1, params, segmentIndex)?.let { return it }
                }
                segment.children.values.filterIsInstance<TemplateSegment>().forEach { child ->
                    matchSegment(child, parts, index, params, segmentIndex)?.let { return it }
                }
                segment.anchors.values.forEach { anchor ->
                    matchAnchor(anchor, parts, index, params, segmentIndex)?.let { return it }
                }
                return null
            }

            is TemplateSegment -> {
                if (index >= parts.size) return null
                val value = if (segment.children.isEmpty() && segment.anchors.isEmpty()) {
                    parts.drop(index).joinToString("/")
                } else {
                    parts[index]
                }
                val nextParams = params + (segment.paramName to value)
                val nextIndex = segmentIndex + (segment.segmentId to value)
                val nextPartIndex = if (segment.children.isEmpty() && segment.anchors.isEmpty()) {
                    parts.size
                } else {
                    index + 1
                }
                if (nextPartIndex >= parts.size) {
                    return ResourceRouteMatch(segment, AncestorContext(nextParams, nextIndex.toMap()))
                }
                val nextPart = parts[nextPartIndex]
                segment.children[nextPart]?.let { child ->
                    matchSegment(child, parts, nextPartIndex + 1, nextParams, nextIndex)?.let { return it }
                }
                segment.children.values.filterIsInstance<TemplateSegment>().forEach { child ->
                    matchSegment(child, parts, nextPartIndex, nextParams, nextIndex)?.let { return it }
                }
                segment.anchors.values.forEach { anchor ->
                    matchAnchor(anchor, parts, nextPartIndex, nextParams, nextIndex)?.let { return it }
                }
                return null
            }
        }
    }

    private fun matchAnchor(
        anchor: ResourceSegment,
        parts: List<String>,
        index: Int,
        params: Map<String, String>,
        segmentIndex: List<Pair<SegmentId, String>>,
    ): ResourceRouteMatch? {
        return when (anchor) {
            is StaticSegment -> {
                if (anchor.name == parts.getOrNull(index)) {
                    matchSegment(anchor, parts, index + 1, params, segmentIndex)
                } else {
                    null
                }
            }

            is TemplateSegment -> matchSegment(anchor, parts, index, params, segmentIndex)
        }
    }
}

private data class ParsedWorkspaceUri(
    val instanceKey: String,
    val path: String,
)

internal data class ResourceRouteMatch(
    val segment: ResourceSegment,
    val ancestors: AncestorContext,
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
        val segmentMatch = snapshot.segmentMatch(resourceUri) ?: return null
        val templateSegment = segmentMatch.segment as? TemplateSegment ?: return null
        val matchedTemplateUri = snapshot.templateUri(templateSegment.segmentId) ?: return null
        if (matchedTemplateUri != resourceTemplate.uriTemplate) return null
        return MatchResult(variables = segmentMatch.ancestors, score = 100)
    }
}
