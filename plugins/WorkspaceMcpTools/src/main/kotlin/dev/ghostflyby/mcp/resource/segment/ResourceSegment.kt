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

package dev.ghostflyby.mcp.resource.segment

import io.modelcontextprotocol.kotlin.sdk.types.ReadResourceResult

/**
 * A node in the resource URI tree. Each segment contributes one path element
 * to the final MCP resource or resource template URI.
 *
 * [StaticSegment] is a literal path element (no parameters) and appears in
 * `resources/list`. [TemplateSegment] contains a `{paramName}` and appears in
 * `resources/templates/list`.
 */
internal sealed class ResourceSegment {
    abstract val segmentId: SegmentId
    abstract val name: String
    abstract val extensible: Boolean

    /**
     * Direct children keyed by their segment name (for [StaticSegment]) or
     * by `{paramName}` (for [TemplateSegment]).
     */
    val children: MutableMap<String, ResourceSegment> = linkedMapOf()

    /**
     * Cross-feature anchor point children. Other features can hang their
     * subtrees here via [ResourceSegmentBuilder.under].
     */
    val anchors: MutableMap<SegmentId, ResourceSegment> = linkedMapOf()
}

/**
 * A literal path element such as `"projects"` or `"server"`.
 * Static segments are listable resources when they are leaves without anchors.
 */
internal class StaticSegment(
    override val segmentId: SegmentId,
    override val name: String,
    override val extensible: Boolean = false,
    val handler: (suspend (params: Map<String, String>, anc: AncestorContext) -> ReadResourceResult)? = null,
) : ResourceSegment()

/**
 * A parameterized path element such as `"{projectKey}"` or `"{relativePath}"`.
 * Template segments appear in `resources/templates/list`.
 */
internal class TemplateSegment(
    override val segmentId: SegmentId,
    override val name: String,
    val paramName: String,
    override val extensible: Boolean = false,
    val handler: suspend (params: Map<String, String>, anc: AncestorContext) -> ReadResourceResult,
) : ResourceSegment()

