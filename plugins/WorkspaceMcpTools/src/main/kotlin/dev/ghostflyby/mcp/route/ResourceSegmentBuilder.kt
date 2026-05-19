/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

internal interface ResourceSegmentBuilder {
    fun resource(
        listProvider: ConcreteResourceListProvider? = null,
        handler: ResourceReadHandler,
    )

    fun template(
        listProvider: TemplateResourceListProvider? = null,
    )

    /**
     * Define a route using a Ktor-like pattern string.
     * The route leaf exports [anchor] if provided — other features can
     * attach sub-routes via [under] with the same [RouteAnchor].
     */
    fun route(
        pattern: String,
        anchor: RouteAnchor? = null,
        block: RouteDslBuilder.() -> Unit,
    )

    /**
     * Attach route children under a [RouteAnchor] exported by another feature's route.
     * The anchor's segment must have [ResourceSegment.extensible] = true.
     */
    fun under(anchor: RouteAnchor, block: ResourceSegmentBuilder.() -> Unit)
}

// ---- RouteDslBuilder (standalone, does NOT implement ResourceSegmentBuilder) ----

/**
 * Collects only resource/template endpoints for a single route pattern.
 * Does NOT expose segment/parameter/under — those don't apply inside a route block.
 */
internal class RouteDslBuilder {
    var resourceEndpoint: ResourceEndpoint? = null
        private set
    var templateEndpoint: ResourceTemplateEndpoint? = null
        private set

    fun resource(
        listProvider: ConcreteResourceListProvider? = null,
        handler: ResourceReadHandler,
    ) {
        check(resourceEndpoint == null) { "Resource endpoint is already set for this route" }
        resourceEndpoint = ResourceEndpoint(handler = handler, listProvider = listProvider)
    }

    fun template(listProvider: TemplateResourceListProvider? = null) {
        check(templateEndpoint == null) { "Template endpoint is already set for this route" }
        templateEndpoint = ResourceTemplateEndpoint(listProvider = listProvider)
    }
}

// ---- Pending anchor ----

internal data class PendingAnchor(
    val segments: List<ResourceSegment>,
    val routeAnchor: RouteAnchor,
)

// ---- ResourceSegmentCollector ----

internal open class ResourceSegmentCollector(
    private val current: ResourceSegment? = null,
) : ResourceSegmentBuilder {
    internal val _roots: MutableList<ResourceSegment> = mutableListOf()
    internal val _pendingAnchors: MutableList<PendingAnchor> = mutableListOf()

    val roots: List<ResourceSegment> get() = _roots.toList()
    val pendingAnchors: List<PendingAnchor> get() = _pendingAnchors.toList()

    override fun resource(
        listProvider: ConcreteResourceListProvider?,
        handler: ResourceReadHandler,
    ) {
        val target = current ?: error("Inline resource endpoint requires a current path segment.")
        target.resourceEndpoints += ResourceEndpointEntry(
            endpoint = ResourceEndpoint(handler = handler, listProvider = listProvider),
        )
    }

    override fun template(listProvider: TemplateResourceListProvider?) {
        val target = current ?: error("Template endpoint requires a current path segment.")
        check(target.templateEndpoint == null) {
            "Resource template endpoint is already registered for segment '${target.name}'."
        }
        target.templateEndpoint = ResourceTemplateEndpoint(listProvider = listProvider)
    }

    override fun route(
        pattern: String,
        anchor: RouteAnchor?,
        block: RouteDslBuilder.() -> Unit,
    ) {
        val patternObj = RoutePattern.parse(pattern)
        val dsb = RouteDslBuilder().apply(block)
        val segments = buildTreeFromPattern(patternObj, dsb.resourceEndpoint, dsb.templateEndpoint)
        segments.forEach { seg ->
            if (anchor != null) {
                val leaf = findLeaf(seg)
                leaf.routeAnchor = anchor
            }
            // Merge into existing root with same name
            val existing = _roots.find { it.name == seg.name }
            if (existing != null) {
                existing.resourceEndpoints += seg.resourceEndpoints
                if (seg.templateEndpoint != null && existing.templateEndpoint == null) {
                    existing.templateEndpoint = seg.templateEndpoint
                }
            } else {
                _roots.add(seg)
            }
        }
    }

    override fun under(anchor: RouteAnchor, block: ResourceSegmentBuilder.() -> Unit) {
        val collector = ResourceSegmentCollector()
        block(collector)
        _pendingAnchors.add(
            PendingAnchor(
                segments = collector.roots.toList(),
                routeAnchor = anchor,
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
        segment.children = segment.children.builder().apply { collector.roots.forEach { put(it.name, it) } }.build()
        attachChild(segment)
        _pendingAnchors.addAll(collector.pendingAnchors)
    }

    private fun attachChild(segment: ResourceSegment) {
        val parent = current
        if (parent == null) {
            _roots.add(segment)
        } else {
            parent.children = parent.children.builder().apply { put(segment.name, segment) }.build()
        }
    }
}

// ---- Tree-building helpers ----

/**
 * Walk down a single-chain segment tree to find the leaf (deepest node).
 */
internal fun findLeaf(segment: ResourceSegment): ResourceSegment {
    var current = segment
    while (current.children.isNotEmpty()) {
        current = current.children.values.first()
    }
    return current
}

/**
 * Build a segment tree from a parsed [RoutePattern].
 * Each path token becomes a segment node; the last token (leaf) gets the endpoints.
 */
internal fun buildTreeFromPattern(
    pattern: RoutePattern,
    resourceEndpoint: ResourceEndpoint?,
    templateEndpoint: ResourceTemplateEndpoint?,
): List<ResourceSegment> {
    val tokens = pattern.pathTokens
    if (tokens.isEmpty()) return emptyList()

    val root = tokenToSegment(tokens.first())
    var current = root
    for (i in 1 until tokens.size) {
        val child = tokenToSegment(tokens[i])
        current.children = current.children.builder().apply { put(child.name, child) }.build()
        current = child
    }
    if (resourceEndpoint != null) {
        current.resourceEndpoints += ResourceEndpointEntry(
            endpoint = resourceEndpoint,
            queryTokens = pattern.queryTokens,
        )
    }
    if (templateEndpoint != null) current.templateEndpoint = templateEndpoint
    current.routePattern = pattern

    return listOf(root)
}

private fun tokenToSegment(token: PathToken): ResourceSegment {
    return when (token) {
        is LiteralToken -> LiteralPathSegment(name = token.text)
        is ParamToken -> ParameterPathSegment(name = "{${token.name}}", paramName = token.name)
        is ReservedParamToken -> ParameterPathSegment(name = "{+${token.name}}", paramName = token.name, extensible = true)
    }
}
