/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

internal interface ResourceSegmentBuilder {
    fun segment(
        name: String,
        id: SegmentId = SegmentId.next(),
        extensible: Boolean = false,
        block: ResourceSegmentBuilder.() -> Unit = {},
    )

    fun parameter(
        paramName: String,
        id: SegmentId = SegmentId.next(),
        extensible: Boolean = false,
        block: ResourceSegmentBuilder.() -> Unit = {},
    )

    fun resource(
        name: String,
        id: SegmentId = SegmentId.next(),
        extensible: Boolean = false,
        listProvider: ConcreteResourceListProvider? = null,
        handler: ResourceReadHandler,
    )

    fun resource(
        listProvider: ConcreteResourceListProvider? = null,
        handler: ResourceReadHandler,
    )

    fun template(
        listProvider: TemplateResourceListProvider? = null,
    )

    fun under(anchor: SegmentId, block: ResourceSegmentBuilder.() -> Unit = {})
}

internal class ResourceSegmentCollector(
    private val current: ResourceSegment? = null,
) : ResourceSegmentBuilder {
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
        val segment = LiteralPathSegment(
            segmentId = id,
            name = name,
            extensible = extensible,
        )
        collectChild(segment, block)
    }

    override fun parameter(
        paramName: String,
        id: SegmentId,
        extensible: Boolean,
        block: ResourceSegmentBuilder.() -> Unit,
    ) {
        val segment = ParameterPathSegment(
            segmentId = id,
            name = "{$paramName}",
            paramName = paramName,
            extensible = extensible,
        )
        collectChild(segment, block)
    }

    override fun resource(
        name: String,
        id: SegmentId,
        extensible: Boolean,
        listProvider: ConcreteResourceListProvider?,
        handler: ResourceReadHandler,
    ) {
        val segment = LiteralPathSegment(
            segmentId = id,
            name = name,
            extensible = extensible,
        )
        segment.resourceEndpoint = ResourceEndpoint(
            handler = handler,
            listProvider = listProvider,
        )
        attachChild(segment)
    }

    override fun resource(
        listProvider: ConcreteResourceListProvider?,
        handler: ResourceReadHandler,
    ) {
        val target = current ?: error("Inline resource endpoint requires a current path segment.")
        check(target.resourceEndpoint == null) {
            "Resource endpoint is already registered for segment '${target.name}'."
        }
        target.resourceEndpoint = ResourceEndpoint(
            handler = handler,
            listProvider = listProvider,
        )
    }

    override fun template(listProvider: TemplateResourceListProvider?) {
        val target = current ?: error("Template endpoint requires a current path segment.")
        check(target.templateEndpoint == null) {
            "Resource template endpoint is already registered for segment '${target.name}'."
        }
        target.templateEndpoint = ResourceTemplateEndpoint(listProvider = listProvider)
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
        _pendingAnchors.addAll(collector.pendingAnchors)
    }

    private fun collectChild(
        segment: ResourceSegment,
        block: ResourceSegmentBuilder.() -> Unit,
    ) {
        val collector = ResourceSegmentCollector(segment)
        collector.block()
        segment.children.putAll(collector.roots.associateBy { it.name })
        attachChild(segment)
        _pendingAnchors.addAll(collector.pendingAnchors)
    }

    private fun attachChild(segment: ResourceSegment) {
        val parent = current
        if (parent == null) {
            _roots.add(segment)
        } else {
            parent.children[segment.name] = segment
        }
    }
}

internal data class PendingAnchor(
    val targetId: SegmentId,
    val segments: List<ResourceSegment>,
)
