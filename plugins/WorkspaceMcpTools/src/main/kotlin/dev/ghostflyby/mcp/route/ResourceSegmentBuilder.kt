/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import io.modelcontextprotocol.kotlin.sdk.types.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/**
 * Collects route tree fragments for a feature registration.
 */
internal open class ResourceSegmentCollector {
    internal val _roots: MutableList<ResourceSegment> = mutableListOf()

    val roots: List<ResourceSegment> get() = _roots.toList()

    internal fun addResourceRoute(
        resourceType: KType,
        serializer: KSerializer<*> = serializer(resourceType),
        readRoute: ResourceReadRoute? = null,
        resourceListRoute: ResourceListRoute? = null,
        templateListRoute: ResourceTemplateListRoute? = null,
    ) {
        val info = ResourceClassInfo.from(serializer.descriptor)
        val format = WorkspaceResourceUriFormat()
        val paramDeserializer: ((Map<String, String>) -> Any?)? = { params ->
            format.decodeFromParams(params, info, serializer)
        }
        val segments = buildTreeFromResourceClass(
            info = info,
            paramDeserializer = paramDeserializer,
            readRoute = readRoute,
            resourceListRoute = resourceListRoute,
            templateListRoute = templateListRoute,
        )
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

internal inline fun <reified T : Any> ResourceSegmentCollector.read(
    noinline handler: suspend McpCallContext<ReadResourceRequest>.(T) -> ReadResourceResult,
) {
    addResourceRoute(
        resourceType = typeOf<T>(),
        serializer = serializer<T>(),
        readRoute = ResourceReadRoute(
            invoker = { context, resource ->
                handler(context, resource as T)
            },
        ),
    )
}

internal inline fun <reified T : Any> ResourceSegmentCollector.listResources(
    noinline listProvider: (suspend McpCallContext<ListResourcesRequest>.() -> ResourceListDecision<Resource>)? = null,
) {
    addResourceRoute(
        resourceType = typeOf<T>(),
        serializer = serializer<T>(),
        resourceListRoute = ResourceListRoute(provider = listProvider),
    )
}

internal inline fun <reified T : Any> ResourceSegmentCollector.listTemplates(
    noinline listProvider: (suspend McpCallContext<ListResourceTemplatesRequest>.() -> ResourceListDecision<ResourceTemplate>)? = null,
) {
    addResourceRoute(
        resourceType = typeOf<T>(),
        serializer = serializer<T>(),
        templateListRoute = ResourceTemplateListRoute(provider = listProvider),
    )
}

internal fun mergeSegment(
    target: ResourceSegment,
    source: ResourceSegment,
) {
    target.readRoutes = target.readRoutes.builder()
        .apply { source.readRoutes.forEach { add(it) } }
        .build()
    if (source.resourceListRoute != null && target.resourceListRoute == null) {
        target.resourceListRoute = source.resourceListRoute
    }
    if (source.templateListRoute != null && target.templateListRoute == null) {
        target.templateListRoute = source.templateListRoute
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
    paramDeserializer: ((Map<String, String>) -> Any?)? = null,
    readRoute: ResourceReadRoute? = null,
    resourceListRoute: ResourceListRoute? = null,
    templateListRoute: ResourceTemplateListRoute? = null,
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
    readRoute?.let { route ->
        val copiedRoute = route.copy(
            resourceClassInfo = info,
            paramDeserializer = paramDeserializer,
        )
        current.readRoutes = current.readRoutes.builder().apply { add(copiedRoute) }.build()
    }
    if (resourceListRoute != null) current.resourceListRoute = resourceListRoute.copy(resourceClassInfo = info)
    if (templateListRoute != null) current.templateListRoute = templateListRoute.copy(resourceClassInfo = info)

    return listOf(root)
}

private fun pathInfoToSegment(seg: PathSegmentInfo): ResourceSegment {
    return when {
        seg.isTail -> ParameterPathSegment(name = seg.text, paramName = seg.paramName!!, extensible = true)
        seg.isParameter -> ParameterPathSegment(name = seg.text, paramName = seg.paramName!!)
        else -> LiteralPathSegment(name = seg.text)
    }
}
