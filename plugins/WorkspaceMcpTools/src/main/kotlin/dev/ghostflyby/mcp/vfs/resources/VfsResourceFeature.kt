/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.vfs.resources

import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeature
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistration
import dev.ghostflyby.mcp.sdk.WorkspaceMcpFeatureRegistrationContext
import dev.ghostflyby.mcp.vfs.tools.*

/**
 * VFS resource feature: provides project-scoped file and VFS resource templates
 * via the Ktor-like route DSL. Attached to the CoreResourceFeature.PROJECT_ROUTE anchor.
 */
internal class VfsResourceFeature : WorkspaceMcpFeature {
    override val featureName: String = "vfs-resources"

    override fun WorkspaceMcpFeatureRegistrationContext.register(): WorkspaceMcpFeatureRegistration {
        registerToolClass<VfsSdkTools>()
        return buildRegistration()
    }
}
