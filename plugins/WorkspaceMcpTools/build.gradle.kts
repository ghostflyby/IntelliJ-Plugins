/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.aware.SplitModeAware

plugins {
    id("repo.intellij-plugin")
}

version = "2.1.0"

dependencies {
    intellijPlatform {
        pluginModule(implementation(project(":plugins:WorkspaceMcpTools:shared")))
        pluginModule(implementation(project(":plugins:WorkspaceMcpTools:frontend")))
        // The backend module's classes are composed into the main plugin jar (embedded content
        // module) so its classloader is the main one, which also sees lib/*.jar — the documented
        // plugin-with-dependencies model. A separate content-module classloader only sees its own
        // lib/modules/<module-id>.jar, where our ktor jars do not live.
        pluginComposedModule(implementation(project(":plugins:WorkspaceMcpTools:backend")))
        testFramework(TestFrameworkType.JUnit5)
    }

}

intellijPlatform {
    // Plugin Model v2: run the backend and the frontend (JetBrains Client) as separate local
    // processes; install the plugin into both sandboxes so both sides load their modules.
    splitMode = true
    pluginInstallationTarget = SplitModeAware.PluginInstallationTarget.BOTH
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(rootProject.layout.projectDirectory.dir(".agents/skills/workspace-agent-bridge")) {
        into(pluginName.map { "$it/agent-skills/workspace-agent-bridge" })
    }
}

configurations.all {
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}
