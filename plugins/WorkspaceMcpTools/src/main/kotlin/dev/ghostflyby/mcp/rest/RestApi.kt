/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.server.routing.Route
import io.ktor.server.routing.route

/**
 * Mounts all REST API routes under /api/v1.
 * Query parameters are declared in [@Resource][io.ktor.resources.Resource] classes
 * from modules/workspace-mcp-server (e.g. [VfsResource][dev.ghostflyby.mcp.server.route.resources.VfsResource]).
 */
internal fun Route.restApi() {
    route("/api/v1") {
        serverInfoRoutes()
        projectRoutes()
        fileRoutes()
    }
}
