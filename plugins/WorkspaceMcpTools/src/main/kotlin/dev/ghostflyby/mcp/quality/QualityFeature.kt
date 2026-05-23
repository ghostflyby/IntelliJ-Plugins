/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.quality

import dev.ghostflyby.mcp.quality.tools.QualityTools
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext

internal class QualityFeature : WorkspaceMcpFeature {
    override val featureName: String = "quality"

    override fun WorkspaceMcpFeatureRegistrationContext.register() {
        registerToolClass<QualityTools>()

    }
}
