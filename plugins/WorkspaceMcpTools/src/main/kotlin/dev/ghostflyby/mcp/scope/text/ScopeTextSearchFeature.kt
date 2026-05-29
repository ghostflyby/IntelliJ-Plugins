/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.scope.text

import dev.ghostflyby.mcp.scope.text.tools.ScopeTextSearchTools
import dev.ghostflyby.mcp.server.WorkspaceMcpFeature
import dev.ghostflyby.mcp.server.WorkspaceMcpFeatureRegistrationContext

internal class ScopeTextSearchFeature : WorkspaceMcpFeature {
    override val featureName: String = "scope-text-search"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        registerToolClass<ScopeTextSearchTools>()

    }
}
