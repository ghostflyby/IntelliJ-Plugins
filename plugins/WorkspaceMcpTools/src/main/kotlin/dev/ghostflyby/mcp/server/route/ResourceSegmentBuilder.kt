/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer

/**
 * Collects route tree fragments for a feature registration.
 */
internal open class ResourceSegmentCollector {
    internal val resourceSegments: MutableList<ResourceSegment> = mutableListOf()
    internal val listRoutes: MutableList<ResourceListRoute> = mutableListOf()
    internal val resourceTemplateListRoutes: MutableList<ResourceTemplateListRoute> = mutableListOf()

    val roots: List<ResourceSegment> get() = resourceSegments.toList()
    val resourceListRoutes: List<ResourceListRoute> get() = listRoutes.toList()
    val templateListRoutes: List<ResourceTemplateListRoute> get() = resourceTemplateListRoutes.toList()

    internal fun addReadRoute(
        serializer: KSerializer<*>,
        readRoute: ResourceReadRoute,
    ) {
        val info = ResourceClassInfo.from(serializer.descriptor)
        val format = WorkspaceResourceUriFormat()
        val paramDeserializer: (Map<String, String>) -> Any? = { params ->
            format.decodeFromParams(params, info, serializer)
        }
        val segments = buildReadTreeFromResourceClass(
            info = info,
            paramDeserializer = paramDeserializer,
            readRoute = readRoute,
        )
        segments.forEach { seg ->
            val existing = resourceSegments.find { it.name == seg.name }
            if (existing != null) {
                mergeSegment(existing, seg)
            } else {
                resourceSegments.add(seg)
            }
        }
    }

    internal fun addResourceListRoute(
        serializer: KSerializer<*>,
        route: ResourceListRoute,
    ) {
        val info = ResourceClassInfo.from(serializer.descriptor)
        listRoutes += route.copy(
            listKey = info.templateUri(),
            resourceClassInfo = info,
        )
    }

    internal fun addTemplateListRoute(
        serializer: KSerializer<*>,
        route: ResourceTemplateListRoute,
    ) {
        val info = ResourceClassInfo.from(serializer.descriptor)
        resourceTemplateListRoutes += route.copy(
            listKey = info.templateUri(),
            resourceClassInfo = info,
        )
    }
}

internal inline fun <reified T : Any> ResourceSegmentCollector.read(
    noinline handler: suspend McpCallContext<ReadResourceRequest>.(T) -> ReadResourceResult,
) {
    addReadRoute(
        serializer = serializer<T>(),
        readRoute = ResourceReadRoute(
            invoker = { context, resource ->
                handler(context, resource as T)
            },
        ),
    )
}

internal inline fun <reified T : Any> ResourceSegmentCollector.listResources(
    noinline listProvider: (suspend McpCallContext<ListResourcesRequest>.() -> List<Resource>),
) {
    addResourceListRoute(
        serializer = serializer<T>(),
        route = ResourceListRoute(
            listKey = "",
            provider = listProvider,
        ),
    )
}

internal inline fun <reified T : Any> ResourceSegmentCollector.listTemplates(
    noinline listProvider: (suspend McpCallContext<ListResourceTemplatesRequest>.() -> List<ResourceTemplate>),
) {
    addTemplateListRoute(
        serializer = serializer<T>(),
        route = ResourceTemplateListRoute(
            listKey = "",
            provider = listProvider,
        ),
    )
}

internal fun mergeSegment(
    target: ResourceSegment,
    source: ResourceSegment,
) {
    target.readRoutes = target.readRoutes.builder()
        .apply { source.readRoutes.forEach { add(it) } }
        .build()
    source.children.values.forEach { sourceChild ->
        val targetChild = target.children[sourceChild.name]
        if (targetChild != null) {
            mergeSegment(targetChild, sourceChild)
        } else {
            target.children = target.children.builder().apply { put(sourceChild.name, sourceChild) }.build()
        }
    }
}

internal fun buildReadTreeFromResourceClass(
    info: ResourceClassInfo,
    paramDeserializer: ((Map<String, String>) -> Any?)? = null,
    readRoute: ResourceReadRoute,
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
    val copiedRoute = readRoute.copy(
        resourceClassInfo = info,
        paramDeserializer = paramDeserializer,
    )
    current.readRoutes = current.readRoutes.builder().apply { add(copiedRoute) }.build()

    return listOf(root)
}

private fun pathInfoToSegment(seg: PathSegmentInfo): ResourceSegment {
    return when {
        seg.isTail -> ParameterPathSegment(name = seg.text, paramName = seg.paramName!!, extensible = true)
        seg.isParameter -> ParameterPathSegment(name = seg.text, paramName = seg.paramName!!)
        else -> LiteralPathSegment(name = seg.text)
    }
}
