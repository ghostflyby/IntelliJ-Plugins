/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.modelcontextprotocol.kotlin.sdk.types.Resource
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import kotlinx.collections.immutable.persistentHashMapOf

internal object ResourceRouteCompiler {
    fun compile(registrations: Collection<WorkspaceResourceRouteContribution>): ResourceRouteSnapshot {
        val roots = linkedMapOf<String, ResourceSegment>()

        registrations.forEach { contribution ->
            contribution.roots.forEach { root ->
                val clonedRoot = root.cloneWithoutAnchors()
                if (clonedRoot.name in roots) {
                    val existing = roots[clonedRoot.name]!!
                    mergeSegment(existing, clonedRoot)
                } else {
                    roots[clonedRoot.name] = clonedRoot
                }
            }
        }

        val resources = mutableListOf<ResourceRouteResource>()
        roots.values.forEach { root ->
            enumerate(
                segment = root,
                inheritedOwnerFeatureName = ownerByRootName(registrations, root.name),
                prefix = "", resources = resources,
            )
        }
        val resourceListRoutes = registrations.flatMap { it.resourceListRoutes }
        val templateListRoutes = registrations.flatMap { it.templateListRoutes }
        val resourceListKeys = resourceListRoutes.mapTo(linkedSetOf()) { it.listKey }
        val templateListKeys = templateListRoutes.mapTo(linkedSetOf()) { it.listKey }

        return ResourceRouteSnapshot(
            resources = resources.toList(),
            defaultFallbacks = resources
                .filter { !it.isParameterized && it.uri !in resourceListKeys }
                .map { ReadRouteDefault.ResourceEntry(it.defaultResource()) } +
                    resources
                        .filter { it.isParameterized && it.uri !in templateListKeys }
                        .map { ReadRouteDefault.TemplateEntry(it.defaultTemplate()) },
            resourceListRoutes = resourceListRoutes,
            templateListRoutes = templateListRoutes,
            routeRoots = roots.toMap(),
            parameterizedResourceByUri = resources
                .filter { it.isParameterized }
                .associateBy { it.uri },
        )
    }

    private fun enumerate(
        segment: ResourceSegment,
        inheritedOwnerFeatureName: String,
        prefix: String,
        resources: MutableList<ResourceRouteResource>,
        hasParameter: Boolean = false,
    ) {
        val ownerFeatureName = segment.ownerFeatureName ?: inheritedOwnerFeatureName
        val currentPath = if (prefix.isEmpty()) segment.name else "$prefix/${segment.name}"
        val currentHasParameter = hasParameter || segment is ParameterPathSegment

        segment.readRoutes.forEach { entry ->
            val uri = entry.resourceClassInfo?.templateUri()
                ?: routeUri(currentPath)
            resources += ResourceRouteResource(
                uri = uri,
                name = segment.name, description = entry.description, mimeType = entry.mimeType,
                ownerFeatureName = ownerFeatureName, invoker = entry.invoker,
                readRoute = entry,
                isParameterized = currentHasParameter,
            )
        }

        segment.children.values.forEach {
            enumerate(it, ownerFeatureName, currentPath, resources, currentHasParameter)
        }
    }

    private fun routeUri(
        path: String,
    ): String {
        return "ij-workspace://{instanceKey}/$path"
    }

    private fun ownerByRootName(
        registrations: Collection<WorkspaceResourceRouteContribution>,
        rootName: String,
    ): String {
        return registrations.firstOrNull { contribution ->
            contribution.roots.any { it.name == rootName }
        }?.featureName ?: "unknown"
    }

    private fun ResourceSegment.cloneWithoutAnchors(): ResourceSegment {
        val clone = when (this) {
            is LiteralPathSegment -> LiteralPathSegment(name = name, extensible = extensible)
            is ParameterPathSegment -> ParameterPathSegment(name = name, paramName = paramName, extensible = extensible)
        }
        clone.ownerFeatureName = ownerFeatureName
        clone.readRoutes = readRoutes
        clone.children =
            children.values.fold(persistentHashMapOf<String, ResourceSegment>().builder()) { builder, child ->
                val childClone = child.cloneWithoutAnchors()
                builder[childClone.name] = childClone
                builder
            }.build()
        return clone
    }
}

private fun ResourceRouteResource.defaultResource(): Resource {
    return Resource(
        uri = uri,
        name = name,
        description = description.ifBlank { null },
        mimeType = mimeType,
    )
}

private fun ResourceRouteResource.defaultTemplate(): ResourceTemplate {
    return ResourceTemplate(
        uriTemplate = uri,
        name = name,
        description = description.ifBlank { null },
        mimeType = mimeType,
    )
}

internal fun ResourceClassInfo.templateUri(): String {
    return "ij-workspace://{instanceKey}/${pathTemplate()}${queryTemplate()}"
}

internal fun ResourceClassInfo.pathTemplate(): String {
    return pathSegments.joinToString("/") { segment ->
        when {
            segment.isParameter -> "{${segment.paramName}}"
            else -> segment.text
        }
    }
}

internal fun ResourceClassInfo.queryTemplate(): String {
    return if (queryParams.isNotEmpty()) {
        "{?${queryParams.joinToString(",") { it.name }}}"
    } else ""
}

internal data class WorkspaceResourceRouteContribution(
    val featureName: String,
    val roots: List<ResourceSegment>,
    val resourceListRoutes: List<ResourceListRoute> = emptyList(),
    val templateListRoutes: List<ResourceTemplateListRoute> = emptyList(),
)
