/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal interface ResourceSegmentBuilder {
    /** Register a read handler for the current path segment. */
    fun read(handler: ResourceReadHandler)

    /** Set the resource list provider for the current path segment. */
    fun listResources(listProvider: ConcreteResourceListProvider? = null)

    /** Set the template list provider for the current path segment. */
    fun listTemplates(listProvider: TemplateResourceListProvider? = null)
}

/**
 * Define a route using a Ktor [io.ktor.resources.Resource] class.
 */
internal inline fun <reified T : Any> ResourceSegmentBuilder.route(
    noinline block: RouteDslBuilder.() -> Unit,
) {
    (this as ResourceSegmentCollector).routeWithType(typeOf<T>(), block)
}

/**
 * Collects read/list endpoints for a single resource route.
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

/**
 * Collects route tree fragments for a feature registration.
 */
internal open class ResourceSegmentCollector(
    private val current: ResourceSegment? = null,
) : ResourceSegmentBuilder {
    internal val _roots: MutableList<ResourceSegment> = mutableListOf()

    val roots: List<ResourceSegment> get() = _roots.toList()

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

    internal fun routeWithType(
        resourceType: KType,
        block: RouteDslBuilder.() -> Unit,
    ) {
        val info = ResourceClassInfo.from(serializer(resourceType).descriptor)
        val dsb = RouteDslBuilder().apply(block)
        val segments = buildTreeFromResourceClass(info, dsb)
        segments.forEach { seg ->
            val existing = _roots.find { it.name == seg.name }
            if (existing != null) {
                mergeSegment(existing, seg)
            } else {
                _roots.add(seg)
            }
        }
    }
}

internal fun mergeSegment(
    target: ResourceSegment,
    source: ResourceSegment,
) {
    target.readEntries = target.readEntries.builder()
        .apply { source.readEntries.forEach { add(it) } }
        .build()
    if (source.resourceList != null && target.resourceList == null) {
        target.resourceList = source.resourceList
    }
    if (source.templateList != null && target.templateList == null) {
        target.templateList = source.templateList
    }
    source.children.values.forEach { sourceChild ->
        val targetChild = target.children[sourceChild.name]
        if (targetChild != null) {
            mergeSegment(targetChild, sourceChild)
        } else {
            target.children = target.children.builder().apply { put(sourceChild.name, sourceChild) }.build()
        }
    }
}

internal fun buildTreeFromResourceClass(
    info: ResourceClassInfo,
    dsb: RouteDslBuilder,
): List<ResourceSegment> {
    val pathSegments = info.pathSegments
    if (pathSegments.isEmpty()) return emptyList()

    val root = pathInfoToSegment(pathSegments.first())
    var current = root
    for (i in 1 until pathSegments.size) {
        val child = pathInfoToSegment(pathSegments[i])
        current.children = current.children.builder().apply { put(child.name, child) }.build()
        current = child
    }
    if (dsb.readHandler != null) {
        val entry = dsb.readHandler!!.copy(resourceClassInfo = info)
        current.readEntries = current.readEntries.builder().apply { add(entry) }.build()
    }
    if (dsb.resourceList != null) current.resourceList = dsb.resourceList!!.copy(resourceClassInfo = info)
    if (dsb.templateList != null) current.templateList = dsb.templateList!!.copy(resourceClassInfo = info)

    return listOf(root)
}

private fun pathInfoToSegment(seg: PathSegmentInfo): ResourceSegment {
    return when {
        seg.isTail -> ParameterPathSegment(name = seg.text, paramName = seg.paramName!!, extensible = true)
        seg.isParameter -> ParameterPathSegment(name = seg.text, paramName = seg.paramName!!)
        else -> LiteralPathSegment(name = seg.text)
    }
}
