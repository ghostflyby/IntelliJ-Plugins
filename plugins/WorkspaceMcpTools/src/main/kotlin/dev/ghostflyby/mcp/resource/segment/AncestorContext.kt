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

/**
 * Merged params + ancestor-segment index. Implements [Map] by delegation to
 * the MCP SDK template variables, while also supporting semantic lookup via
 * [SegmentId] with [get] operator.
 *
 * Usage:
 *   anc["projectKey"]        — standard Map access (delegates to vars map)
 *   anc[PROJECT_SEGMENT]     — semantic access via SegmentId
 */
internal class AncestorContext(
    params: Map<String, String>,
    segmentIndex: Map<SegmentId, String> = emptyMap(),
) : Map<String, String> by params {
    private val index: Map<SegmentId, String> = segmentIndex
    operator fun get(segmentId: SegmentId): String? = index[segmentId]
}
