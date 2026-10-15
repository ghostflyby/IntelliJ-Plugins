/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import java.net.URLEncoder

internal fun List<String>.toRoutePath(): String = joinToString("/")

internal fun encodeRoutePathSegment(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

internal fun joinRelativePath(base: String, child: String): String =
    if (base.isBlank()) child else "$base/$child"
