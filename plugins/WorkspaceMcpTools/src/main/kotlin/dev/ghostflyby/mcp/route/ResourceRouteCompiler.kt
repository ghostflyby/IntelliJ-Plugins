/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

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
        val templates = mutableListOf<ResourceRouteTemplate>()
        roots.values.forEach { root ->
            enumerate(
                segment = root,
                inheritedOwnerFeatureName = ownerByRootName(registrations, root.name),
                prefix = "", resources = resources, templates = templates,
            )
        }

        return ResourceRouteSnapshot(
            resources = resources.toList(),
            templates = templates.toList(),
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
        templates: MutableList<ResourceRouteTemplate>,
        hasParameter: Boolean = false,
    ) {
        val ownerFeatureName = segment.ownerFeatureName ?: inheritedOwnerFeatureName
        val currentPath = if (prefix.isEmpty()) segment.name else "$prefix/${segment.name}"
        val currentHasParameter = hasParameter || segment is ParameterPathSegment

        segment.readEntries.forEach { entry ->
            val uri = routeUri(currentPath, entry.resourceClassInfo)
            resources += ResourceRouteResource(
                uri = uri,
                name = segment.name, description = entry.description, mimeType = entry.mimeType,
                ownerFeatureName = ownerFeatureName, handler = entry.handler,
                readEntry = entry,
                resourceListProvider = segment.resourceList?.listProvider,
                isParameterized = currentHasParameter,
            )
        }
        segment.templateList?.let { spec ->
            val uri = routeUri(currentPath, spec.resourceClassInfo)
            templates += ResourceRouteTemplate(
                uri = uri,
                name = segment.name, description = spec.description, mimeType = spec.mimeType,
                ownerFeatureName = ownerFeatureName,
                templateListProvider = spec.listProvider,
                templateListSpec = spec,
            )
        }

        segment.children.values.forEach {
            enumerate(it, ownerFeatureName, currentPath, resources, templates, currentHasParameter)
        }
    }

    private fun routeUri(
        path: String,
        info: ResourceClassInfo?,
    ): String {
        val querySuffix = info?.queryTemplate() ?: ""
        val pathTemplate = path.split("/").joinToString("/") { part ->
            when {
                part.startsWith("{") && part.endsWith("...}") -> "{${part.substring(1, part.length - 4)}}"
                else -> part
            }
        }
        return "ij-workspace://{instanceKey}/$pathTemplate$querySuffix"
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
        clone.readEntries = readEntries
        clone.templateList = templateList
        clone.resourceList = resourceList
        clone.children =
            children.values.fold(persistentHashMapOf<String, ResourceSegment>().builder()) { builder, child ->
                val childClone = child.cloneWithoutAnchors()
                builder[childClone.name] = childClone
                builder
            }.build()
        return clone
    }
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
)
