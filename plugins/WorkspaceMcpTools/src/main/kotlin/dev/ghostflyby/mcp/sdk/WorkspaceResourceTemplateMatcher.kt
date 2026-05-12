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

import dev.ghostflyby.mcp.resource.DOCUMENT_RESOURCE_PREFIX
import dev.ghostflyby.mcp.resource.VFS_RESOURCE_PREFIX
import io.modelcontextprotocol.kotlin.sdk.types.ResourceTemplate
import io.modelcontextprotocol.kotlin.sdk.utils.MatchResult
import io.modelcontextprotocol.kotlin.sdk.utils.PathSegmentTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcher
import io.modelcontextprotocol.kotlin.sdk.utils.ResourceTemplateMatcherFactory

internal object WorkspaceResourceTemplateMatcherFactory : ResourceTemplateMatcherFactory {

    override fun create(resourceTemplate: ResourceTemplate): ResourceTemplateMatcher {
        val prefix = when (resourceTemplate.uriTemplate) {
            WORKSPACE_VFS_RESOURCE_TEMPLATE -> VFS_RESOURCE_PREFIX
            WORKSPACE_DOCUMENT_RESOURCE_TEMPLATE -> DOCUMENT_RESOURCE_PREFIX
            else -> return PathSegmentTemplateMatcher.factory.create(resourceTemplate)
        }
        return WorkspaceRawVfsUrlMatcher(resourceTemplate, prefix)
    }
}

private class WorkspaceRawVfsUrlMatcher(
    override val resourceTemplate: ResourceTemplate,
    private val prefix: String,
) : ResourceTemplateMatcher {

    override fun match(resourceUri: String): MatchResult? {
        if (!resourceUri.startsWith(prefix)) {
            return null
        }
        val rawVfsUrl = resourceUri.removePrefix(prefix)
        if (rawVfsUrl.isBlank()) {
            return null
        }
        return MatchResult(
            variables = mapOf(RAW_VFS_URL_VARIABLE to rawVfsUrl),
            score = WORKSPACE_TEMPLATE_MATCH_SCORE,
        )
    }
}

internal const val WORKSPACE_VFS_RESOURCE_TEMPLATE = "ij-workspace-vfs://{rawVfsUrl}"
internal const val WORKSPACE_DOCUMENT_RESOURCE_TEMPLATE = "ij-workspace-document://{rawVfsUrl}"
internal const val RAW_VFS_URL_VARIABLE = "rawVfsUrl"

private const val WORKSPACE_TEMPLATE_MATCH_SCORE = 100
