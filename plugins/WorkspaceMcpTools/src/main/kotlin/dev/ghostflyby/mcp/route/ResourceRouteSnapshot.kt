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
    val readEntry: ReadEntry,
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
    val templateListSpec: TemplateListSpec,
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
            if (candidate.embedsQuestionMark && match.segment.isTailSegment() != true) {
                continue
            }
            return match.acceptQuery(candidate.queryString) ?: continue
        }
        return null
    }

    internal fun parseWorkspaceUri(uri: String): ParsedWorkspaceUri? {
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
                    return if (segment.hasEndpoint()) {
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
                return null
            }

            is ParameterPathSegment -> {
                if (index >= parts.size) return null
                val value = if (segment.extensible) {
                    parts.drop(index).joinToString("/")
                } else {
                    parts[index]
                }
                val nextParams = params + (segment.paramName to value)
                val nextPartIndex = if (segment.extensible) {
                    parts.size
                } else {
                    index + 1
                }
                if (nextPartIndex >= parts.size) {
                    return if (segment.hasEndpoint()) {
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
                return null
            }
        }
    }

    private fun ResourceSegment.hasEndpoint(): Boolean {
        return readEntries.isNotEmpty() || resourceList != null || templateList != null
    }

    private fun ResourceSegment.isTailSegment(): Boolean {
        return this is ParameterPathSegment && extensible
    }

    private fun ResourceRouteMatch.acceptQuery(queryString: String): ResourceRouteMatch? {
        return segment.endpointInfos().firstNotNullOfOrNull { info ->
            matchQuery(info, queryString)
        }
    }

    internal fun ResourceRouteMatch.matchReadEntry(
        readEntry: ReadEntry,
        queryString: String,
    ): ResourceRouteMatch? {
        val info = readEntry.resourceClassInfo ?: return this.takeIf { queryString.isEmpty() }
        return this.matchQuery(info, queryString)
    }

    private fun ResourceSegment.endpointInfos(): List<ResourceClassInfo?> {
        return readEntries.map { it.resourceClassInfo } +
                listOfNotNull(resourceList?.resourceClassInfo, templateList?.resourceClassInfo)
    }

    private fun ResourceRouteMatch.matchQuery(
        info: ResourceClassInfo?,
        queryString: String,
    ): ResourceRouteMatch? {
        if (info == null) return this.takeIf { queryString.isEmpty() }
        val queryParams = info.queryParams
        if (queryParams.isEmpty()) return this.takeIf { queryString.isEmpty() }

        val hasRequired = queryParams.any { !it.isOptional }
        if (queryString.isEmpty()) {
            return if (hasRequired) null else this
        }

        val actualParams = queryString.split("&").filter { it.isNotBlank() }.associate { pair ->
            val eqIdx = pair.indexOf('=')
            if (eqIdx < 0) pair to null else pair.substring(0, eqIdx) to pair.substring(eqIdx + 1)
        }
        val knownParamNames = queryParams.map { it.name }.toSet()
        if (actualParams.keys.none { it in knownParamNames }) return null

        val queryCaptures = mutableMapOf<String, String>()
        for (qp in queryParams) {
            if (actualParams.containsKey(qp.name)) {
                queryCaptures[qp.name] = actualParams[qp.name] ?: ""
            } else {
                if (!qp.isOptional) return null
            }
        }

        return if (queryCaptures.isEmpty()) this
        else ResourceRouteMatch(
            segment = segment,
            ancestors = AncestorContext(ancestors.toMutableMap().apply { putAll(queryCaptures) }),
        )
    }
}

internal data class ParsedWorkspaceUri(
    val instanceKey: String,
    val pathAndQuery: String,
) {
    fun pathCandidates(): Sequence<ParsedWorkspacePathCandidate> = sequence {
        val questionMarks = pathAndQuery.indices.filter { pathAndQuery[it] == '?' }
        for (questionMark in questionMarks.asReversed()) {
            yield(
                ParsedWorkspacePathCandidate(
                    path = pathAndQuery.substring(0, questionMark),
                    queryString = pathAndQuery.substring(questionMark + 1),
                    embedsQuestionMark = false,
                ),
            )
        }
        yield(
            ParsedWorkspacePathCandidate(
                path = pathAndQuery,
                queryString = "",
                embedsQuestionMark = questionMarks.isNotEmpty(),
            ),
        )
    }
}

internal data class ParsedWorkspacePathCandidate(
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
        if (segmentMatch.segment.readEntries.none { it.handler === entry.handler }) return null
        val parsed = snapshot.parseWorkspaceUri(resourceUri) ?: return null
        for (candidate in parsed.pathCandidates()) {
            val endpointMatch = snapshot.run {
                segmentMatch.matchReadEntry(entry.readEntry, candidate.queryString)
            } ?: continue
            return MatchResult(variables = endpointMatch.ancestors, score = 100)
        }
        return null
    }
}
