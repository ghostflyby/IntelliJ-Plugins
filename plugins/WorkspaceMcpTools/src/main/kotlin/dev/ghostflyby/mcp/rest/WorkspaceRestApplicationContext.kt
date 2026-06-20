/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.rest

import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.routing.*
import io.ktor.util.*

internal data class WorkspaceRestApplicationContext(
    val port: Int,
    val instanceKey: String,
    val version: String,
)

private val WorkspaceRestApplicationContextKey =
    AttributeKey<WorkspaceRestApplicationContext>("WorkspaceRestApplicationContext")

internal fun Application.installWorkspaceRestContext(context: WorkspaceRestApplicationContext) {
    attributes.put(WorkspaceRestApplicationContextKey, context)
}

internal fun Application.workspaceRestContext(): WorkspaceRestApplicationContext {
    return attributes[WorkspaceRestApplicationContextKey]
}

internal fun Application.installWorkspaceRestApi(context: WorkspaceRestApplicationContext) {
    installWorkspaceRestContext(context)
    installWorkspaceRestContentNegotiation()
    install(Resources)
    routing { restApi() }
}
