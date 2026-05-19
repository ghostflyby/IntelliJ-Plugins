/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import java.util.concurrent.atomic.AtomicReference

internal data class ResourceRouteResource(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val ownerFeatureName: String,
    val handler: ResourceReadHandler,
    val resourceListProvider: ConcreteResourceListProvider?,
    val isParameterized: Boolean,
)

internal data class ResourceRouteTemplate(
    val uri: String,
    val name: String,
    val description: String,
    val mimeType: String,
    val ownerFeatureName: String,
    val templateListProvider: TemplateResourceListProvider?,
)

internal data class ResourceRouteSnapshot(
    val resources: List<ResourceRouteResource> = emptyList(),
    val templates: List<ResourceRouteTemplate> = emptyList(),
    val routeRoots: Map<String, ResourceSegment> = emptyMap(),
    private val parameterizedResourceByUri: Map<String, ResourceRouteResource> = emptyMap(),
) {
    fun parameterizedResource(uriTemplate: String): ResourceRouteResource? =
        parameterizedResourceByUri[uriTemplate]

    fun segmentMatch(uri: String): ResourceRouteMatch? {
        val parsed = parseWorkspaceUri(uri) ?: return null
        for (candidate in parsed.pathCandidates()) {
            val parts = candidate.path.removePrefix("/").split("/")
            val root = routeRoots[parts.firstOrNull()] ?: continue
            val match = matchSegment(
                segment = root,
                parts = parts,
                index = 1,
            params = mapOf("instanceKey" to parsed.instanceKey),
        ) ?: continue
            if (candidate.embedsQuestionMark && match.segment.routePattern?.hasReservedParam != true) {
                continue
            }
            return matchQueryOnMatch(match, candidate.queryString) ?: continue
        }
        return null
    }

    private fun parseWorkspaceUri(uri: String): ParsedWorkspaceUri? {
        val schemeEnd = uri.indexOf("://")
        if (schemeEnd < 0) return null
        val afterScheme = uri.substring(schemeEnd + 3)
        val firstSlash = afterScheme.indexOf('/')
        if (firstSlash < 0) return null
        val instanceKey = afterScheme.substring(0, firstSlash)
        if (instanceKey.isBlank()) return null
        return ParsedWorkspaceUri(
            instanceKey = instanceKey,
            pathAndQuery = afterScheme.substring(firstSlash),
        )
    }

    private fun matchSegment(
        segment: ResourceSegment,
        parts: List<String>,
        index: Int,
        params: Map<String, String>,
    ): ResourceRouteMatch? {
        when (segment) {
            is LiteralPathSegment -> {
                if (index >= parts.size) {
                    return if (segment.resourceEndpoint != null || segment.templateEndpoint != null) {
                        ResourceRouteMatch(segment, AncestorContext(params))
                    } else {
                        null
                    }
                }
                val nextPart = parts[index]
                segment.children[nextPart]?.let { child ->
                    matchSegment(child, parts, index + 1, params)?.let { return it }
                }
                segment.children.values.filterIsInstance<ParameterPathSegment>().forEach { child ->
                    matchSegment(child, parts, index, params)?.let { return it }
                }
                segment.attachedSegments.forEach { anchor ->
                    matchAnchor(anchor, parts, index, params)?.let { return it }
                }
                return null
            }

            is ParameterPathSegment -> {
                if (index >= parts.size) return null
                val value = if (segment.children.isEmpty() && segment.attachedSegments.isEmpty()) {
                    parts.drop(index).joinToString("/")
                } else {
                    parts[index]
                }
                val nextParams = params + (segment.paramName to value)
                val nextPartIndex = if (segment.children.isEmpty() && segment.attachedSegments.isEmpty()) {
                    parts.size
                } else {
                    index + 1
                }
                if (nextPartIndex >= parts.size) {
                    return if (segment.resourceEndpoint != null || segment.templateEndpoint != null) {
                        ResourceRouteMatch(segment, AncestorContext(nextParams))
                    } else {
                        null
                    }
                }
                val nextPart = parts[nextPartIndex]
                segment.children[nextPart]?.let { child ->
                    matchSegment(child, parts, nextPartIndex + 1, nextParams)?.let { return it }
                }
                segment.children.values.filterIsInstance<ParameterPathSegment>().forEach { child ->
                    matchSegment(child, parts, nextPartIndex, nextParams)?.let { return it }
                }
                segment.attachedSegments.forEach { anchor ->
                    matchAnchor(anchor, parts, nextPartIndex, nextParams)?.let { return it }
                }
                return null
            }
        }
    }

    private fun matchAnchor(
        anchor: ResourceSegment,
        parts: List<String>,
        index: Int,
        params: Map<String, String>,
    ): ResourceRouteMatch? {
        return when (anchor) {
            is LiteralPathSegment -> {
                if (anchor.name == parts.getOrNull(index)) {
                    matchSegment(anchor, parts, index + 1, params)
                } else {
                    null
                }
            }
            is ParameterPathSegment -> matchSegment(anchor, parts, index, params)
        }
    }

    /**
     * Validate query tokens from the matched segment's routePattern against
     * the URI's query string. Captures param tokens into [AncestorContext].
     * Returns null when a required literal or key-only token fails to match.
     */
    private fun matchQueryOnMatch(
        match: ResourceRouteMatch,
        queryString: String,
    ): ResourceRouteMatch? {
        val queryTokens = match.segment.routePattern?.queryTokens ?: return match
        if (queryTokens.isEmpty()) return if (queryString.isEmpty()) match else null
        val actualParams = queryString.split("&").filter { it.isNotBlank() }.associate { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) pair to null else pair.substring(0, eqIdx) to pair.substring(eqIdx + 1)
        }
        val queryCaptures = mutableMapOf<String, String>()
        for (token in queryTokens) {
            val actualValue = actualParams[token.key]
            when {
                token.paramName != null -> {
                    if (actualValue == null) return null
                    queryCaptures[token.paramName] = actualValue
                }
                token.literalValue != null -> {
                    if (actualValue != token.literalValue) return null
                }
                else -> {
                    if (!actualParams.containsKey(token.key)) return null
                }
            }
        }
        if (queryCaptures.isEmpty()) return match
        return ResourceRouteMatch(
            segment = match.segment,
            ancestors = AncestorContext(match.ancestors.toMutableMap().apply { putAll(queryCaptures) }),
        )
    }
}

private data class ParsedWorkspaceUri(
    val instanceKey: String,
    val pathAndQuery: String,
) {
    fun pathCandidates(): Sequence<ParsedWorkspacePathCandidate> = sequence {
        val questionMarks = pathAndQuery.indices.filter { pathAndQuery[it] == '?' }
        yield(
            ParsedWorkspacePathCandidate(
                path = pathAndQuery,
                queryString = "",
                embedsQuestionMark = questionMarks.isNotEmpty(),
            ),
        )
        for (questionMark in questionMarks.asReversed()) {
            yield(
                ParsedWorkspacePathCandidate(
                    path = pathAndQuery.substring(0, questionMark),
                    queryString = pathAndQuery.substring(questionMark + 1),
                    embedsQuestionMark = false,
                ),
            )
        }
    }
}

private data class ParsedWorkspacePathCandidate(
    val path: String,
    val queryString: String,
    val embedsQuestionMark: Boolean,
)

internal data class ResourceRouteMatch(
    val segment: ResourceSegment,
    val ancestors: AncestorContext,
)

internal class ResourceRouteSnapshotRef(
    initialSnapshot: ResourceRouteSnapshot = ResourceRouteSnapshot(),
) {
    private val ref = AtomicReference(initialSnapshot)

    fun get(): ResourceRouteSnapshot = ref.get()
    fun set(snapshot: ResourceRouteSnapshot) {
        ref.set(snapshot)
    }
}

internal class SegmentTreeTemplateMatcher(
    override val resourceTemplate: ResourceTemplate,
    private val snapshotRef: ResourceRouteSnapshotRef,
) : ResourceTemplateMatcher {
    override fun match(resourceUri: String): MatchResult? {
        val snapshot = snapshotRef.get()
        val entry = snapshot.parameterizedResource(resourceTemplate.uriTemplate) ?: return null
        val segmentMatch = snapshot.segmentMatch(resourceUri) ?: return null
        val endpoint = segmentMatch.segment.resourceEndpoint ?: return null
        if (endpoint.handler !== entry.handler) return null
        return MatchResult(variables = segmentMatch.ancestors, score = 100)
    }
}
