/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

internal interface ResourceSegmentBuilder {
    /** Register a read handler for the current path segment. */
    fun read(handler: ResourceReadHandler)

    /** Set the resource list provider for the current path segment. */
    fun listResources(listProvider: ConcreteResourceListProvider? = null)

    /** Set the template list provider for the current path segment. */
    fun listTemplates(listProvider: TemplateResourceListProvider? = null)

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

// ---- RouteDslBuilder ----

/**
 * Collects read/list endpoints for a single route pattern.
 */
internal class RouteDslBuilder {
    var readHandler: ReadEntry? = null
        private set
    var resourceList: ResourceListSpec? = null
        private set
    var templateList: TemplateListSpec? = null
        private set

    fun read(handler: ResourceReadHandler) {
        check(readHandler == null) { "Read handler is already set for this route" }
        readHandler = ReadEntry(handler = handler)
    }

    fun listResources(listProvider: ConcreteResourceListProvider? = null) {
        resourceList = ResourceListSpec(listProvider = listProvider)
    }

    fun listTemplates(listProvider: TemplateResourceListProvider? = null) {
        check(templateList == null) { "Template list is already set for this route" }
        templateList = TemplateListSpec(listProvider = listProvider)
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

    override fun read(handler: ResourceReadHandler) {
        val target = current ?: error("Inline read handler requires a current path segment.")
        val entry = ReadEntry(handler = handler)
        target.readEntries = target.readEntries.builder().apply { add(entry) }.build()
    }

    override fun listResources(listProvider: ConcreteResourceListProvider?) {
        val target = current ?: error("Resource list requires a current path segment.")
        target.resourceList = ResourceListSpec(listProvider = listProvider)
    }

    override fun listTemplates(listProvider: TemplateResourceListProvider?) {
        val target = current ?: error("Template list requires a current path segment.")
        check(target.templateList == null) {
            "Template list is already set for segment '${target.name}'."
        }
        target.templateList = TemplateListSpec(listProvider = listProvider)
    }

    override fun route(
        pattern: String,
        anchor: RouteAnchor?,
        block: RouteDslBuilder.() -> Unit,
    ) {
        val patternObj = RoutePattern.parse(pattern)
        val dsb = RouteDslBuilder().apply(block)
        val segments = buildTreeFromPattern(patternObj, dsb)
        segments.forEach { seg ->
            if (anchor != null) {
                val leaf = findLeaf(seg)
                leaf.routeAnchor = anchor
            }
            val existing = _roots.find { it.name == seg.name }
            if (existing != null) {
                existing.readEntries = existing.readEntries.builder()
                    .apply { seg.readEntries.forEach { add(it) } }
                    .build()
                if (seg.resourceList != null && existing.resourceList == null) {
                    existing.resourceList = seg.resourceList
                }
                if (seg.templateList != null && existing.templateList == null) {
                    existing.templateList = seg.templateList
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

    private fun attachChild(segment: ResourceSegment) {
        if (current == null) {
            _roots.add(segment)
        } else {
            current.children = current.children.builder().apply { put(segment.name, segment) }.build()
        }
    }
}

// ---- Tree-building helpers ----

internal fun findLeaf(segment: ResourceSegment): ResourceSegment {
    var current = segment
    while (current.children.isNotEmpty()) {
        current = current.children.values.first()
    }
    return current
}

internal fun buildTreeFromPattern(
    pattern: RoutePattern,
    dsb: RouteDslBuilder,
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
    if (dsb.readHandler != null) {
        val entry = dsb.readHandler!!.copy(queryTokens = pattern.queryTokens)
        current.readEntries = current.readEntries.builder().apply { add(entry) }.build()
    }
    if (dsb.resourceList != null) current.resourceList = dsb.resourceList
    if (dsb.templateList != null) current.templateList = dsb.templateList
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
