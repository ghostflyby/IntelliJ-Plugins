/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.filecontent

import dev.ghostflyby.mcp.filecontent.tools.FileContentWriteTools
import dev.ghostflyby.mcp.server.WorkspaceMcpFeature
import dev.ghostflyby.mcp.server.WorkspaceMcpFeatureRegistrationContext

/**
 * File content feature: resource reads migrated to REST API at /api/v1/.
 * File writes remain as MCP tools.
 */
internal class FileContentFeature : WorkspaceMcpFeature {
    override val featureName: String = "file-content"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        registerToolClass<FileContentWriteTools>()
    }
}
