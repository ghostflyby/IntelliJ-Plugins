/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.excludeKotlinStdlib
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    id("repo.intellij-plugin")
    alias(libs.plugins.kotlin.serialization)
}

version = "2.0.0"

buildLogic {
    platformVersion = "2026.1"
    pluginSinceBuild = "261"
}

dependencies {
    implementation(libs.ktor.resources)

    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.snakeyaml)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.cio)
    implementation(project(":modules:intellij-shared"))

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher) {
        excludeKotlinStdlib()
    }
    intellijPlatform {
        pluginComposedModule(project(":modules:intellij-shared"))
        testFramework(TestFrameworkType.JUnit5)
        bundledModule("intellij.platform.vcs.impl")
    }
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", true)
}

tasks.withType<PrepareSandboxTask>().configureEach {
    from(rootProject.layout.projectDirectory.dir(".agents/skills/workspace-mcp-rest-api")) {
        into(pluginName.map { "$it/agent-skills/workspace-mcp-rest-api" })
    }
}

configurations.all {
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}
