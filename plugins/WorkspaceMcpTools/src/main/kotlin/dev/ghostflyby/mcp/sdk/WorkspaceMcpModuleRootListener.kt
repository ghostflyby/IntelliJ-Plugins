/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

package dev.ghostflyby.mcp.sdk

import com.intellij.openapi.components.service
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener

internal class WorkspaceMcpModuleRootListener : ModuleRootListener {
    override fun rootsChanged(event: ModuleRootEvent) {
        service<WorkspaceMcpStateFlows>().globalResourcesChanged()
    }
}
