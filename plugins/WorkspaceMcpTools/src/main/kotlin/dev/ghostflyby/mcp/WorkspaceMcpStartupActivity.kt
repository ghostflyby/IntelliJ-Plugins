/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import dev.ghostflyby.mcp.sdk.WorkspaceMcpSdkServerService

internal class WorkspaceMcpStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (shouldStartWorkspaceMcpServer()) {
            service<WorkspaceMcpSdkServerService>()
        }
    }
}

internal fun shouldStartWorkspaceMcpServer(): Boolean {
    return !ApplicationManager.getApplication().isUnitTestMode
}
