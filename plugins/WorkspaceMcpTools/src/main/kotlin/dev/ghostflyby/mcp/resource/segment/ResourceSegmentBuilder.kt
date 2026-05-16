/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult
import io.modelcontextprotocol.kotlin.sdk.types.Request

/**
 * Resource segment tree registration entry point.
 *
 * Features receive a [ResourceSegmentBuilder] root and attach resource
 * segments via [segment], [template], and [under].
 */
internal interface ResourceSegmentBuilder {
    /**
     * Register a static (literal) path segment and recurse via [block].
     * @param id Optional unique identifier; generated via [SegmentId.next] by default.
     */
    fun segment(
        name: String,
        id: SegmentId = SegmentId.next(),
        extensible: Boolean = false,
        handler: (suspend (params: Map<String, String>, anc: AncestorContext, request: Request?) -> ReadResourceResult)? = null,
        block: ResourceSegmentBuilder.() -> Unit = {},
    )

    /**
     * Register a template (parameterised) path segment.
     * The [paramName] is the URL parameter name, e.g. `"projectKey"` for `{projectKey}`.
     * @param id Optional unique identifier.
     */
    fun template(
        paramName: String,
        id: SegmentId = SegmentId.next(),
        extensible: Boolean = false,
        handler: suspend (params: Map<String, String>, anc: AncestorContext, request: Request?) -> ReadResourceResult,
    )

    /**
     * Hang a sub-tree under an anchor segment exported by another feature.
     * The anchor must have [extensible] set to `true`.
     */
    fun under(anchor: SegmentId, block: ResourceSegmentBuilder.() -> Unit = {})
}

/**
 * Internal implementation of [ResourceSegmentBuilder] that accumulates into a
 * list of root-level [ResourceSegment] entries.
 */
internal class ResourceSegmentCollector : ResourceSegmentBuilder {
    private val _roots: MutableList<ResourceSegment> = mutableListOf()
    private val _pendingAnchors: MutableList<PendingAnchor> = mutableListOf()

    val roots: List<ResourceSegment> get() = _roots.toList()
    val pendingAnchors: List<PendingAnchor> get() = _pendingAnchors.toList()

    override fun segment(
        name: String,
        id: SegmentId,
        extensible: Boolean,
        handler: (suspend (params: Map<String, String>, anc: AncestorContext, request: Request?) -> ReadResourceResult)?,
        block: ResourceSegmentBuilder.() -> Unit,
    ) {
        val seg = StaticSegment(
            segmentId = id, name = name, extensible = extensible, handler = handler,
        )
        val collector = ResourceSegmentCollector()
        collector.block()
        seg.children.putAll(collector.roots.associateBy { it.name })
        seg.anchors.putAll(buildAnchorMap(collector.roots))
        _roots.add(seg)
        _pendingAnchors.addAll(collector.pendingAnchors)
    }

    override fun template(
        paramName: String,
        id: SegmentId,
        extensible: Boolean,
        handler: suspend (params: Map<String, String>, anc: AncestorContext, request: Request?) -> ReadResourceResult,
    ) {
        val seg = TemplateSegment(
            segmentId = id,
            name = "{$paramName}",
            paramName = paramName,
            extensible = extensible,
            handler = handler,
        )
        _roots.add(seg)
    }

    override fun under(anchor: SegmentId, block: ResourceSegmentBuilder.() -> Unit) {
        val collector = ResourceSegmentCollector()
        collector.block()
        _pendingAnchors.add(
            PendingAnchor(
                targetId = anchor,
                segments = collector.roots,
            ),
        )
    }

    private fun buildAnchorMap(segments: List<ResourceSegment>): Map<SegmentId, ResourceSegment> =
        segments.filter { it.extensible }.associateBy { it.segmentId }
}

/**
 * A deferred cross-feature anchor mount. Collected during registration and
 * resolved by [ResourceSegmentRegistry] after all features have registered.
 */
internal data class PendingAnchor(
    val targetId: SegmentId,
    val segments: List<ResourceSegment>,
)

/**
 * Result of a URI match against the resource segment tree.
 */
internal data class ResourceMatchResult(
    /** Matched leaf segment (never null for successful matches). */
    val segment: ResourceSegment,
    /** Parameters captured from template segments along the path. */
    val params: Map<String, String>,
    /** Ancestor parameters indexed by [SegmentId]. */
    val anc: AncestorContext,
)

