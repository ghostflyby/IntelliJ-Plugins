/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
}

// The platform resolves content-module jars as lib/modules/<module-id>.jar
// (PluginDescriptorLoader.loadPluginSubDescriptors), and the sandbox ships the module's composed
// jar, so its archive name must match the module name declared in the root plugin.xml.
tasks.composedJar {
    archiveFileName = "dev.ghostflyby.mcp.workspace.frontend.jar"
}

dependencies {
    intellijPlatform {
        bundledModule("intellij.platform.frontend")
    }
    implementation(project(":plugins:WorkspaceMcpTools:shared"))
}
