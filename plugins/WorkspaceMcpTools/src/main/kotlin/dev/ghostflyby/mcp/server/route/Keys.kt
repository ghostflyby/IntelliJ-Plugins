/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.ktor.util.AttributeKey

internal object Keys {
    val RouteParameters = AttributeKey<AncestorContext>("routeParams")
}
