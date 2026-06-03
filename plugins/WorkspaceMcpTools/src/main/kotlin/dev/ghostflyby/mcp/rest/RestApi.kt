/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Mounts all REST API routes under /api/v1: read + write.
 */
internal fun Route.restApi() {
    route("/api/v1") {
        serverInfoRoutes()
        projectRoutes()
        fileRoutes()
        fileWriteRoutes()
    }
}
