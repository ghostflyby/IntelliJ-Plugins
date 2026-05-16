/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.resource.segment

import io.modelcontextprotocol.kotlin.sdk.types.Request

/**
 * Manages the global resource segment tree across all features.
 *
 * - Collects root-level segments from each feature via [registerRoot].
 * - Resolves cross-feature anchors (collected as [PendingAnchor]).
 * - Generates MCP `resources/list` and `resources/templates/list` entries.
 * - Routes incoming `resources/read` requests.
 * - Supports dynamic removal of a feature's subtree.
 */
internal class ResourceSegmentRegistry {
    /** Top-level static segments keyed by name. */
    private val roots: MutableMap<String, ResourceSegment> = linkedMapOf()

    /** Index: [SegmentId] → owning segment. */
    private val segmentById: MutableMap<SegmentId, ResourceSegment> = linkedMapOf()

    private val pendingAnchors: MutableList<PendingAnchor> = mutableListOf()

    // -- registration --

    fun registerRoot(segment: ResourceSegment) {
        require(segment.name !in roots) {
            "Duplicate root segment name: ${segment.name}"
        }
        require(segment.segmentId !in segmentById) {
            "Duplicate SegmentId: ${segment.segmentId}"
        }
        roots[segment.name] = segment
        indexTree(segment)
    }

    fun addPendingAnchors(anchors: List<PendingAnchor>) {
        pendingAnchors.addAll(anchors)
    }

    /**
     * Resolve all pending anchors. Must be called after all features have
     * registered their roots. Returns a list of orphan anchor target IDs.
     */
    fun resolveAnchors(): List<SegmentId> {
        val orphans = mutableListOf<SegmentId>()
        for (anchor in pendingAnchors) {
            val target = segmentById[anchor.targetId]
            if (target == null) {
                orphans.add(anchor.targetId)
                continue
            }
            if (!target.extensible) {
                orphans.add(anchor.targetId)
                continue
            }
            for (seg in anchor.segments) {
                target.anchors[seg.segmentId] = seg
                indexTree(seg)
            }
        }
        pendingAnchors.clear()
        return orphans
    }

    // -- resource listing --

    data class ResourceEntry(
        val uri: String,
        val name: String,
        val description: String,
        val mimeType: String,
        val isTemplate: Boolean,
    )

    fun listAll(): List<ResourceEntry> = buildList {
        roots.values.forEach { enumerate(it, "", "", this) }
    }

    fun listables(): List<ResourceEntry> = listAll().filter { !it.isTemplate }

    fun templates(): List<ResourceEntry> = listAll().filter { it.isTemplate }

    // -- URI matching --

    fun match(uri: String, request: Request? = null): ResourceMatchResult? {
        val path = extractPath(uri) ?: return null
        val parts = path.trim('/').split('/')
        if (parts.isEmpty()) return null
        val firstPart = parts[0]
        val root = roots[firstPart] ?: return null
        return matchSegment(root, parts, 1, uri, emptyMap(), emptyList())
    }

    // -- dynamic removal --

    fun removeTree(segmentIdsToRemove: Set<SegmentId>) {
        val rootsToRemove = roots.entries.filter { (_, seg) -> seg.segmentId in segmentIdsToRemove }
        rootsToRemove.forEach { (name, _) -> roots.remove(name) }
        roots.values.forEach { removeFromTree(it, segmentIdsToRemove) }
        segmentById.keys.removeAll(segmentIdsToRemove)
    }

    // -- internal --

    private fun extractPath(uri: String): String? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = uri.substring(schemeEnd + 3)
        val firstSlash = afterScheme.indexOf('/')
        if (firstSlash < 0) return null
        return afterScheme.substring(firstSlash)
    }

    private fun indexTree(segment: ResourceSegment) {
        segmentById[segment.segmentId] = segment
        segment.children.values.forEach { indexTree(it) }
        segment.anchors.values.forEach { indexTree(it) }
    }

    private fun enumerate(
        segment: ResourceSegment,
        prefix: String,
        parentTemplates: String,
        sink: MutableList<ResourceEntry>,
    ) {
        val currentPath = if (prefix.isEmpty()) segment.name else "$prefix/${segment.name}"
        val currentTemplates = if (segment is TemplateSegment) {
            if (parentTemplates.isEmpty()) segment.name else "$parentTemplates/${segment.name}"
        } else {
            parentTemplates
        }
        val isTemplate = segment is TemplateSegment
        val hasChildren = segment.children.isNotEmpty() || segment.anchors.isNotEmpty()

        // StaticSegment: emit as listable if it has a handler (even with children)
        if (!isTemplate && segment is StaticSegment && segment.handler != null) {
            sink.add(
                ResourceEntry(
                    uri = "ij-workspace://{instanceKey}$currentPath",
                    name = segment.name,
                    description = "",
                    mimeType = "application/json",
                    isTemplate = false,
                ),
            )
        }

        // TemplateSegment: always emit as template. If extensible=true, also emit as listable.
        if (isTemplate) {
            sink.add(
                ResourceEntry(
                    uri = buildTemplateUri(currentPath, currentTemplates),
                    name = segment.name,
                    description = "",
                    mimeType = "text/plain",
                    isTemplate = true,
                ),
            )
            if (segment.extensible) {
                sink.add(
                    ResourceEntry(
                        uri = "ij-workspace://{instanceKey}$currentPath",
                        name = segment.name,
                        description = "",
                        mimeType = "application/json",
                        isTemplate = false,
                    ),
                )
            }
        }

        // Recurse children and anchors
        if (isTemplate || hasChildren) {
            segment.children.values.forEach {
                enumerate(it, currentPath, currentTemplates, sink)
            }
            segment.anchors.values.forEach {
                enumerate(it, currentPath, currentTemplates, sink)
            }
        }
    }

    private fun buildTemplateUri(path: String, templates: String): String {
        return "ij-workspace://{instanceKey}/$path"
    }

    private fun matchSegment(
        segment: ResourceSegment,
        parts: List<String>,
        index: Int,
        fullUri: String,
        params: Map<String, String>,
        ancChain: List<Pair<SegmentId, String>>,
    ): ResourceMatchResult? {
        if (segment is StaticSegment) {
            // Check children and anchors for continuation
            if (index >= parts.size) {
                // End of path — return this segment if it has a handler
                return if (segment.handler != null) {
                    ResourceMatchResult(segment, params, AncestorContext(ancChain))
                } else {
                    null
                }
            }
            val nextPart = parts[index]
            // Try children
            segment.children[nextPart]?.let { child ->
                val result = matchSegment(child, parts, index + 1, fullUri, params, ancChain)
                if (result != null) return result
            }
            // Try anchors
            for ((_, anchor) in segment.anchors) {
                if (anchor.name == nextPart || anchor is TemplateSegment) {
                    val result = matchAnchor(anchor, parts, index, fullUri, params, ancChain)
                    if (result != null) return result
                }
            }
            return null
        }

        if (segment is TemplateSegment) {
            val newParams = params + (segment.paramName to parts.getOrElse(index) { "" })
            val newChain = ancChain + (segment.segmentId to parts.getOrElse(index) { "" })

            if (index + 1 >= parts.size) {
                return ResourceMatchResult(segment, newParams, AncestorContext(newChain))
            }

            // Match next part against children or anchors
            val nextPart = parts.getOrElse(index + 1) { "" }
            segment.children[nextPart]?.let { child ->
                val result = matchSegment(child, parts, index + 2, fullUri, newParams, newChain)
                if (result != null) return result
            }
            for ((_, anchor) in segment.anchors) {
                val result = matchAnchor(anchor, parts, index + 1, fullUri, newParams, newChain)
                if (result != null) return result
            }
            return ResourceMatchResult(segment, newParams, AncestorContext(newChain))
        }

        return null
    }

    private fun matchAnchor(
        anchor: ResourceSegment,
        parts: List<String>,
        index: Int,
        fullUri: String,
        params: Map<String, String>,
        ancChain: List<Pair<SegmentId, String>>,
    ): ResourceMatchResult? {
        if (anchor is StaticSegment) {
            if (anchor.name == parts.getOrElse(index) { "" }) {
                return matchSegment(anchor, parts, index + 1, fullUri, params, ancChain)
            }
            return null
        }
        if (anchor is TemplateSegment) {
            return matchSegment(anchor, parts, index, fullUri, params, ancChain)
        }
        return null
    }

    private fun removeFromTree(parent: ResourceSegment, idsToRemove: Set<SegmentId>) {
        parent.children.entries.removeAll { (_, child) ->
            child.segmentId in idsToRemove || child.segmentId in parent.children.values
                .filter { it.segmentId in idsToRemove }
                .map { it.segmentId }
                .toSet()
        }
        parent.anchors.entries.removeAll { (_, child) -> child.segmentId in idsToRemove }
        parent.children.values.forEach { removeFromTree(it, idsToRemove) }
        parent.anchors.values.forEach { removeFromTree(it, idsToRemove) }
    }
}
