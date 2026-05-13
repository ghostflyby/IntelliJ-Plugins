/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 *
 * This file is part of IntelliJ-Plugins by ghostflyby
 *
 * IntelliJ-Plugins by ghostflyby is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, see
 * <https://www.gnu.org/licenses/>.
 */

package dev.ghostflyby.mcp.sdk

import dev.ghostflyby.mcp.resource.*
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.PathSegmentTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory

internal object WorkspaceResourceTemplateMatcherFactory : ResourceTemplateMatcherFactory {

    override fun create(resourceTemplate: ResourceTemplate): ResourceTemplateMatcher {
        val uriTemplate = resourceTemplate.uriTemplate
        if (!uriTemplate.startsWith(WORKSPACE_URI_SCHEME)) {
            return PathSegmentTemplateMatcher.factory.create(resourceTemplate)
        }
        val tailVariable = when {
            uriTemplate.contains(KIND_VFS) || uriTemplate.contains(KIND_DOCUMENT_VFS) -> RAW_VFS_TAIL_VARIABLE
            uriTemplate.contains(KIND_FILES) || uriTemplate.contains(KIND_DOCUMENTS) -> RELATIVE_PATH_VARIABLE
            else -> return PathSegmentTemplateMatcher.factory.create(resourceTemplate)
        }
        return WorkspaceNewSchemeMatcher(resourceTemplate, tailVariable)
    }
}

/**
 * Custom matcher for `ij-workspace://{instanceKey}/projects/{projectKey}/{kind}/{tail}`.
 * Extracts structured prefix as key-value variables and everything after the kind segment
 * as the unparsed raw tail. This preserves `file://`, `jar://`, `jrt://`, multi-slash paths,
 * and relative paths with `/` intact.
 */
private class WorkspaceNewSchemeMatcher(
    override val resourceTemplate: ResourceTemplate,
    private val tailVariable: String,
) : ResourceTemplateMatcher {

    override fun match(resourceUri: String): MatchResult? {
        if (!resourceUri.startsWith(WORKSPACE_URI_SCHEME)) return null
        val afterScheme = resourceUri.removePrefix(WORKSPACE_URI_SCHEME)
        val projectsIdx = afterScheme.indexOf(PROJECTS_SEGMENT)
        if (projectsIdx < 0) return null
        val instanceKey = afterScheme.substring(0, projectsIdx)
        if (instanceKey.isBlank()) return null

        val afterProjects = afterScheme.substring(projectsIdx + PROJECTS_SEGMENT.length)
        val firstSlash = afterProjects.indexOf('/')
        if (firstSlash < 0) return null
        val projectKey = afterProjects.substring(0, firstSlash)
        if (projectKey.isBlank()) return null

        val afterProjectKey = afterProjects.substring(firstSlash + 1)
        val kindEnd = afterProjectKey.indexOf('/')
        if (kindEnd < 0) return null
        val kind = afterProjectKey.substring(0, kindEnd)
        if (kind.isBlank()) return null

        val tail = afterProjectKey.substring(kindEnd + 1)
        if (tail.isBlank()) return null

        return MatchResult(
            variables = mapOf(
                "instanceKey" to instanceKey,
                "projectKey" to projectKey,
                tailVariable to tail,
            ),
            score = WORKSPACE_NEW_SCHEME_MATCH_SCORE,
        )
    }
}

// Template URI patterns (used for registration and matcher dispatch)
internal const val NEW_WORKSPACE_FILES_TEMPLATE = "${WORKSPACE_URI_SCHEME}{instanceKey}/projects/{projectKey}/${KIND_FILES}/{relativePath}"
internal const val NEW_WORKSPACE_DOCUMENTS_TEMPLATE = "${WORKSPACE_URI_SCHEME}{instanceKey}/projects/{projectKey}/${KIND_DOCUMENTS}/{relativePath}"
internal const val NEW_WORKSPACE_VFS_TEMPLATE = "${WORKSPACE_URI_SCHEME}{instanceKey}/projects/{projectKey}/${KIND_VFS}/{rawVfsUrl}"
internal const val NEW_WORKSPACE_DOCUMENT_VFS_TEMPLATE = "${WORKSPACE_URI_SCHEME}{instanceKey}/projects/{projectKey}/${KIND_DOCUMENT_VFS}/{rawVfsUrl}"

internal const val RAW_VFS_TAIL_VARIABLE = "rawVfsUrl"
internal const val RELATIVE_PATH_VARIABLE = "relativePath"

private const val WORKSPACE_NEW_SCHEME_MATCH_SCORE = 100
