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
        // The backend module's classes are composed into the main plugin jar (embedded content
        // module), so they run on the MAIN classloader, which also sees lib/*.jar — the
        // documented plugin-with-dependencies model. A separate content-module classloader
        // would only see its own lib/modules/<module-id>.jar and miss kotlinx-serialization
        // used by the `show` JSON import.
        pluginComposedModule(implementation(project(":plugins:Mill:backend")))
        pluginComposedModule(project(":modules:intellij-shared"))
    }
}

intellijPlatform {
    // Plugin Model v2: everything the plugin ships is backend-native (project import, task
    // execution, tool window and settings over external-system state), so the backend is the
    // only runtime location and split-mode frontends skip the plugin entirely; splitMode keeps
    // the local two-process dev loop (runIdeBackend / runIdeSplitMode).
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BACKEND
}
