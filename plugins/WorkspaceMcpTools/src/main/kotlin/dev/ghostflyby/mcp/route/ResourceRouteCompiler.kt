/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import kotlinx.collections.immutable.persistentHashMapOf
import kotlinx.collections.immutable.persistentListOf

internal object ResourceRouteCompiler {
    fun compile(registrations: Collection<WorkspaceResourceRouteContribution>): ResourceRouteSnapshot {
        val roots = linkedMapOf<String, ResourceSegment>()
        val pendingAnchors = mutableListOf<PendingAnchor>()

        registrations.forEach { contribution ->
            contribution.roots.forEach { root ->
                val clonedRoot = root.cloneWithoutAnchors()
                if (clonedRoot.name in roots) {
                    val existing = roots[clonedRoot.name]!!
                    existing.readEntries = existing.readEntries.addAll(clonedRoot.readEntries)
                    if (clonedRoot.templateList != null && existing.templateList == null) {
                        existing.templateList = clonedRoot.templateList
                    }
                } else {
                    roots[clonedRoot.name] = clonedRoot
                }
            }
            contribution.pendingAnchors.forEach { pendingAnchor ->
                pendingAnchors += PendingAnchor(
                    routeAnchor = pendingAnchor.routeAnchor,
                    segments = pendingAnchor.segments.map { it.cloneWithoutAnchors() },
                )
            }
        }

        // Build route anchor index from segment.routeAnchor markings
        val routeAnchorIndex = mutableMapOf<String, ResourceSegment>()
        roots.values.forEach { root -> indexRouteAnchors(root, routeAnchorIndex) }

        // Resolve RouteAnchor-based anchors by match key
        pendingAnchors.forEach { anchor ->
            val target = routeAnchorIndex[anchor.routeAnchor.key] ?: return@forEach
            anchor.segments.forEach { segment ->
                target.attachedSegments = target.attachedSegments.builder().apply { add(segment) }.build()
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

    private fun indexRouteAnchors(
        segment: ResourceSegment,
        index: MutableMap<String, ResourceSegment>,
    ) {
        if (segment.routeAnchor != null) {
            index[segment.routeAnchor!!.key] = segment
        }
        segment.children.values.forEach { indexRouteAnchors(it, index) }
        segment.attachedSegments.forEach { indexRouteAnchors(it, index) }
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
            val querySuffix = entry.queryTemplate ?: segment.routePattern?.queryTemplate ?: ""
            val baseUri = "ij-workspace://{instanceKey}/$currentPath"
            val uri = if (querySuffix.isNotEmpty()) baseUri + querySuffix else baseUri
            resources += ResourceRouteResource(
                uri = uri,
                name = segment.name, description = entry.description, mimeType = entry.mimeType,
                ownerFeatureName = ownerFeatureName, handler = entry.handler,
                resourceListProvider = segment.resourceList?.listProvider,
                isParameterized = currentHasParameter,
            )
        }
        segment.templateList?.let { spec ->
            val querySuffix = segment.routePattern?.queryTemplate ?: ""
            val baseUri = "ij-workspace://{instanceKey}/$currentPath"
            val uri = if (querySuffix.isNotEmpty()) baseUri + querySuffix else baseUri
            templates += ResourceRouteTemplate(
                uri = uri,
                name = segment.name, description = spec.description, mimeType = spec.mimeType,
                ownerFeatureName = ownerFeatureName,
                templateListProvider = spec.listProvider,
            )
        }

        segment.children.values.forEach {
            enumerate(it, ownerFeatureName, currentPath, resources, templates, currentHasParameter)
        }
        segment.attachedSegments.forEach {
            enumerate(it, ownerFeatureName, currentPath, resources, templates, currentHasParameter)
        }
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
        clone.routePattern = routePattern
        clone.routeAnchor = routeAnchor
        clone.children =
            children.values.fold(persistentHashMapOf<String, ResourceSegment>().builder()) { builder, child ->
                val childClone = child.cloneWithoutAnchors()
                builder[childClone.name] = childClone
                builder
            }.build()
        clone.attachedSegments =
            attachedSegments.fold(persistentListOf<ResourceSegment>().builder()) { builder, child ->
                builder.add(child.cloneWithoutAnchors())
                builder
            }.build()
        return clone
    }
}

internal data class WorkspaceResourceRouteContribution(
    val featureName: String,
    val roots: List<ResourceSegment>,
    val pendingAnchors: List<PendingAnchor>,
)
