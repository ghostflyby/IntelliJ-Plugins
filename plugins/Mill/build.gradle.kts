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
        // The backend module's classes are composed into the main plugin jar (embedded content
        // module), so they run on the MAIN classloader, which also sees lib/*.jar — the
        // documented plugin-with-dependencies model. A separate content-module classloader
        // would only see its own lib/modules/<module-id>.jar and miss kotlinx-serialization
        // used by the `show` JSON import.
        pluginComposedModule(implementation(project(":plugins:Mill:backend")))
    }
}

intellijPlatform {
    // Plugin Model v2 split: the frontend renders settings/tool window, the backend runs the
    // external-system resolver, task manager, data services and host-side executable probing.
    // splitMode keeps the local two-process dev loop (runIdeBackend / runIdeSplitMode).
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
}
