/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.Service
import dev.ghostflyby.mcp.server.WorkspaceMcpSessionState

@Service(Service.Level.APP)
internal class WorkspaceMcpSessionStateService {
    val state: WorkspaceMcpSessionState = WorkspaceMcpSessionState()

    fun hasResourceSubscriptions(): Boolean = state.hasResourceSubscriptions()
}
