/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.route

/**
 * String-keyed anchor for cross-feature route mount points.
 *
 * Features use [RouteAnchor] in `under(anchor)` to hook sub-trees
 * under another feature's route. The anchor [key] is the string
 * key used in the ancestor map (e.g. `"projectKey"`).
 */
@JvmInline
internal value class RouteAnchor(val key: String)
