/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

internal object ResourceRouteCompiler {
    fun compile(registrations: Collection<WorkspaceResourceRouteContribution>): ResourceRouteSnapshot {
        val roots = linkedMapOf<String, ResourceSegment>()
        val segmentById = linkedMapOf<SegmentId, ResourceSegment>()
        val pendingAnchors = mutableListOf<PendingAnchor>()

        registrations.forEach { contribution ->
            contribution.roots.forEach { root ->
                val clonedRoot = root.cloneWithoutAnchors()
                require(clonedRoot.name !in roots) { "Duplicate root segment name: ${clonedRoot.name}" }
                roots[clonedRoot.name] = clonedRoot
                indexTree(clonedRoot, segmentById)
            }
            pendingAnchors += contribution.pendingAnchors.map { anchor ->
                PendingAnchor(
                    targetId = anchor.targetId,
                    segments = anchor.segments.map { it.cloneWithoutAnchors() },
                )
            }
        }

        pendingAnchors.forEach { anchor ->
            val target = segmentById[anchor.targetId] ?: return@forEach
            if (!target.extensible) return@forEach
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
                prefix = "",
                resources = resources,
                templates = templates,
            )
        }
        return ResourceRouteSnapshot(
            resources = resources.toList(),
            templates = templates.toList(),
            roots = roots.toMap(),
            templateUriBySegmentId = templates.associate { it.segmentId to it.uri },
        )
    }

    private fun indexTree(
        segment: ResourceSegment,
        segmentById: MutableMap<SegmentId, ResourceSegment>,
    ) {
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
        paramToSegmentId: Map<String, SegmentId> = emptyMap(),
    ) {
        val ownerFeatureName = segment.ownerFeatureName ?: inheritedOwnerFeatureName
        val currentPath = if (prefix.isEmpty()) segment.name else "$prefix/${segment.name}"
        when (segment) {
            is StaticSegment -> {
                val isContainer = segment.children.isNotEmpty() || segment.anchors.isNotEmpty()
                if (!isContainer) {
                    resources += ResourceRouteResource(
                        uri = "ij-workspace://{instanceKey}/$currentPath",
                        name = segment.name,
                        description = "",
                        mimeType = "application/json",
                        ownerFeatureName = ownerFeatureName,
                        handler = segment.handler,
                    )
                }
                segment.children.values.forEach {
                    enumerate(it, ownerFeatureName, currentPath, resources, templates, paramToSegmentId)
                }
                segment.anchors.values.forEach {
                    enumerate(it, ownerFeatureName, currentPath, resources, templates, paramToSegmentId)
                }
            }

            is TemplateSegment -> {
                templates += ResourceRouteTemplate(
                    uri = "ij-workspace://{instanceKey}/$currentPath",
                    name = segment.name,
                    description = "",
                    mimeType = "text/plain",
                    ownerFeatureName = ownerFeatureName,
                    handler = segment.handler,
                    segmentId = segment.segmentId,
                    paramToSegmentId = paramToSegmentId + (segment.paramName to segment.segmentId),
                )
                val childParams = paramToSegmentId + (segment.paramName to segment.segmentId)
                segment.children.values.forEach {
                    enumerate(it, ownerFeatureName, currentPath, resources, templates, childParams)
                }
                segment.anchors.values.forEach {
                    enumerate(it, ownerFeatureName, currentPath, resources, templates, childParams)
                }
            }
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
            is StaticSegment -> StaticSegment(
                segmentId = segmentId,
                name = name,
                extensible = extensible,
                handler = handler,
            )

            is TemplateSegment -> TemplateSegment(
                segmentId = segmentId,
                name = name,
                paramName = paramName,
                extensible = extensible,
                handler = handler,
            )
        }
        clone.ownerFeatureName = ownerFeatureName
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
