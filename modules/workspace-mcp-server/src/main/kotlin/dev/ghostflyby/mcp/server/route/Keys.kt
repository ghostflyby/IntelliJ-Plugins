/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.server.route

import io.ktor.util.*

public object Keys {
    public val RouteParameters: AttributeKey<AncestorContext> = AttributeKey<AncestorContext>("routeParams")
}
