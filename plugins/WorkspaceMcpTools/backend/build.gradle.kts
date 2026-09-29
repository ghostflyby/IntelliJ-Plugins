/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
    alias(libs.plugins.kotlin.serialization)
}

// The platform resolves content-module jars as lib/modules/<module-id>.jar
// (PluginDescriptorLoader.loadPluginSubDescriptors), and the sandbox ships the module's composed
// jar, so its archive name must match the module name declared in the root plugin.xml.
tasks.composedJar {
    archiveFileName = "dev.ghostflyby.mcp.workspace.backend.jar"
}

dependencies {
    implementation(project(":plugins:WorkspaceMcpTools:shared"))

    // GenericPatchApplier and the rest of com.intellij.openapi.diff.impl live in this module.
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.vcs.impl")
    }

    implementation(libs.ktor.resources)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.cio)
    implementation(libs.snakeyaml)

    testImplementation(libs.ktor.server.test.host)
}
