/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.navigation

import dev.ghostflyby.mcp.navigation.tools.NavigationTools
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

internal class NavigationFeature : WorkspaceMcpFeature {
    override val featureName: String = "navigation"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        registerToolClass<NavigationTools>()

    }
}
