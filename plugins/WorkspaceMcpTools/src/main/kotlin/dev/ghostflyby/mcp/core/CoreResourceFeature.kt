/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.core

import dev.ghostflyby.mcp.server.WorkspaceMcpFeature
import dev.ghostflyby.mcp.server.WorkspaceMcpFeatureRegistrationContext

/**
 * Core metadata feature: previously registered MCP resources for server info
 * and project metadata. Resources migrated to REST API at /api/v1/.
 */
internal class CoreResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "core"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        // Resources migrated to REST API at /api/v1/
    }
}
