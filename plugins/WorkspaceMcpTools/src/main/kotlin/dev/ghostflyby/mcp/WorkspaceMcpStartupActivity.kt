/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ghostflyby.mcp.filecontent.FileContentDocumentListenerService
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSdkServerService
import dev.ghostflyby.mcp.server.WorkspaceMcpStateFlows

internal class WorkspaceMcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<WorkspaceMcpSdkServerService>()
        service<FileContentDocumentListenerService>()
        service<WorkspaceMcpStateFlows>().globalResourcesChanged()
    }
}
