/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

internal class WorkspaceMcpSdkServerStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<WorkspaceMcpSdkServerService>()
    }
}
