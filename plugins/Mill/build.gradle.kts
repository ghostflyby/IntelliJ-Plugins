/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("repo.intellij-plugin")
}

version = "0.0.1"

dependencies {
    intellijPlatform {
        pluginModule(implementation(project(":plugins:Mill:shared")))
        // Settings UI, tool window, actions and the external-system view contributor live in
        // the frontend module, which the platform loads in a split-mode frontend and in the
        // monolith IDE. The shared widgets ship as their own content module so this module's
        // classloader can see them.
        pluginModule(implementation(project(":plugins:Mill:frontend")))
        pluginModule(implementation(project(":modules:intellij-shared")))
        // The backend must be a real content module, not an embedded one: the platform ignores
        // required-if-available for embedded/required modules
        // (PluginContentDescriptor.ModuleItem.determineLoadingRule), so an embedded backend would
        // still load in a split-mode frontend and report intellij.platform.backend as missing.
        // The module jar carries the external-system resolver and is skipped when the backend
        // module is unavailable.
        pluginModule(implementation(project(":plugins:Mill:backend")))
    }
}

intellijPlatform {
    // Plugin Model v2 split: the frontend renders settings/tool window, the backend runs the
    // external-system resolver, task manager, data services and host-side executable probing.
    // splitMode keeps the local two-process dev loop (runIdeBackend / runIdeSplitMode).
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
}
