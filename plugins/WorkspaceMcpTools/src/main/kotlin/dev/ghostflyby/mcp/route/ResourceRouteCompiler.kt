/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

internal object ResourceRouteCompiler {
    fun compile(registrations: Collection<WorkspaceResourceRouteContribution>): ResourceRouteSnapshot {
        val roots = linkedMapOf<String, ResourceSegment>()
        val segmentById = linkedMapOf<SegmentId, ResourceSegment>()
        val pendingAnchors = mutableListOf<PendingAnchor>()
        val routeAnchorAnchors = mutableListOf<PendingAnchor>()

        registrations.forEach { contribution ->
            contribution.roots.forEach { root ->
                val clonedRoot = root.cloneWithoutAnchors()
                require(clonedRoot.name !in roots) { "Duplicate root segment name: ${clonedRoot.name}" }
                roots[clonedRoot.name] = clonedRoot
                indexTree(clonedRoot, segmentById)
            }
            contribution.pendingAnchors.forEach { anchor ->
                if (anchor.routeAnchor != null) {
                    routeAnchorAnchors.add(anchor)
                } else {
                    pendingAnchors += PendingAnchor(
                        targetId = anchor.targetId,
                        segments = anchor.segments.map { it.cloneWithoutAnchors() },
                    )
                }
            }
        }

        // Resolve legacy SegmentId-based anchors
        pendingAnchors.forEach { anchor ->
            val target = segmentById[anchor.targetId] ?: return@forEach
            if (!target.extensible) return@forEach
            anchor.segments.forEach { segment ->
                target.anchors[segment.segmentId] = segment
                indexTree(segment, segmentById)
            }
        }

        // Build route anchor index from segment.routeAnchor markings
        val routeAnchorIndex = mutableMapOf<String, ResourceSegment>()
        roots.values.forEach { root -> indexRouteAnchors(root, routeAnchorIndex) }

        // Resolve RouteAnchor-based anchors by match key
        routeAnchorAnchors.forEach { anchor ->
            val anchorKey = anchor.routeAnchor?.key ?: return@forEach
            val target = routeAnchorIndex[anchorKey] ?: return@forEach
            anchor.segments.forEach { segment ->
                target.anchors[segment.segmentId] = segment
                indexTree(segment, segmentById)
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
        segment.anchors.values.forEach { indexRouteAnchors(it, index) }
    }

    private fun indexTree(segment: ResourceSegment, segmentById: MutableMap<SegmentId, ResourceSegment>) {
        require(segment.segmentId !in segmentById) { "Duplicate SegmentId: ${segment.segmentId}" }
        segmentById[segment.segmentId] = segment
        segment.children.values.forEach { indexTree(it, segmentById) }
        segment.anchors.values.forEach { indexTree(it, segmentById) }
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

        segment.resourceEndpoint?.let { endpoint ->
            val querySuffix = segment.routePattern?.queryTemplate ?: ""
            val baseUri = "ij-workspace://{instanceKey}/$currentPath"
            val uri = if (querySuffix.isNotEmpty()) baseUri + querySuffix else baseUri
            resources += ResourceRouteResource(
                uri = uri,
                name = segment.name, description = endpoint.description, mimeType = endpoint.mimeType,
                ownerFeatureName = ownerFeatureName, handler = endpoint.handler,
                resourceListProvider = endpoint.listProvider,
                isParameterized = currentHasParameter,
            )
        }
        segment.templateEndpoint?.let { endpoint ->
            val querySuffix = segment.routePattern?.queryTemplate ?: ""
            val baseUri = "ij-workspace://{instanceKey}/$currentPath"
            val uri = if (querySuffix.isNotEmpty()) baseUri + querySuffix else baseUri
            templates += ResourceRouteTemplate(
                uri = uri,
                name = segment.name, description = endpoint.description, mimeType = endpoint.mimeType,
                ownerFeatureName = ownerFeatureName,
                templateListProvider = endpoint.listProvider,
            )
        }

        segment.children.values.forEach {
            enumerate(it, ownerFeatureName, currentPath, resources, templates, currentHasParameter)
        }
        segment.anchors.values.forEach {
            enumerate(it, ownerFeatureName, currentPath, resources, templates, currentHasParameter)
        }
    }

    private fun ownerByRootName(registrations: Collection<WorkspaceResourceRouteContribution>, rootName: String): String {
        return registrations.firstOrNull { contribution ->
            contribution.roots.any { it.name == rootName }
        }?.featureName ?: "unknown"
    }

    private fun ResourceSegment.cloneWithoutAnchors(): ResourceSegment {
        val clone = when (this) {
            is LiteralPathSegment -> LiteralPathSegment(segmentId = segmentId, name = name, extensible = extensible)
            is ParameterPathSegment -> ParameterPathSegment(segmentId = segmentId, name = name, paramName = paramName, extensible = extensible)
        }
        clone.ownerFeatureName = ownerFeatureName
        clone.resourceEndpoint = resourceEndpoint
        clone.templateEndpoint = templateEndpoint
        clone.routePattern = routePattern
        clone.routeAnchor = routeAnchor
        children.values.forEach { child ->
            val childClone = child.cloneWithoutAnchors()
            clone.children[childClone.name] = childClone
        }
        return clone
    }
}

internal data class WorkspaceResourceRouteContribution(
    val featureName: String,
    val roots: List<ResourceSegment>,
    val pendingAnchors: List<PendingAnchor>,
)
