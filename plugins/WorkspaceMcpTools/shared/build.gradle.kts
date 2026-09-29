/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
    `java-library`
}

// The platform resolves content-module jars as lib/modules/<module-id>.jar
// (PluginDescriptorLoader.loadPluginSubDescriptors), and the sandbox ships the module's composed
// jar, so its archive name must match the module name declared in the root plugin.xml.
tasks.composedJar {
    archiveFileName = "dev.ghostflyby.mcp.workspace.shared.jar"
}

// Platform and test dependencies come from the repo.intellij-module convention.
