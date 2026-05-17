/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

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
        block: ResourceSegmentBuilder.() -> Unit = {},
    )

    fun resource(
        name: String,
        id: SegmentId = SegmentId.next(),
        extensible: Boolean = false,
        handler: ResourceReadHandler,
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
        handler: ResourceReadHandler,
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
        block: ResourceSegmentBuilder.() -> Unit,
    ) {
        val collector = ResourceSegmentCollector()
        collector.block()
        val seg = StaticSegment(
            segmentId = id,
            name = name,
            extensible = extensible,
            handler = { error("Resource segment '$name' is not directly readable.") },
        )
        seg.children.putAll(collector.roots.associateBy { it.name })
        _roots.add(seg)
        _pendingAnchors.addAll(collector.pendingAnchors)
    }

    override fun resource(
        name: String,
        id: SegmentId,
        extensible: Boolean,
        handler: ResourceReadHandler,
    ) {
        _roots.add(
            StaticSegment(
                segmentId = id,
                name = name,
                extensible = extensible,
                handler = handler,
            ),
        )
    }

    override fun template(
        paramName: String,
        id: SegmentId,
        extensible: Boolean,
        handler: ResourceReadHandler,
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
}

/**
 * A deferred cross-feature anchor mount. Collected during registration and
 * resolved by [ResourceRouteCompiler] after all features have registered.
 */
internal data class PendingAnchor(
    val targetId: SegmentId,
    val segments: List<ResourceSegment>,
)
