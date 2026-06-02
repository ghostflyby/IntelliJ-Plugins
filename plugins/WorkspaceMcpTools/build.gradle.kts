/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.excludeKotlinStdlib
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("repo.intellij-plugin")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

version = "1.0.4"

buildLogic {
    platformVersion = "2026.1"
    pluginSinceBuild = "261"
}

dependencies {
    api(libs.mcp.kotlin.sdk.server)
    api(libs.ktor.resources)
    api(libs.kotlinx.schema.annotations)

    ksp(libs.kotlinx.schema.ksp)

    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.cio)
    implementation(project(":modules:intellij-shared"))
    implementation(project(":modules:workspace-mcp-server"))

    testImplementation(libs.mcp.kotlin.sdk.testing)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher) {
        excludeKotlinStdlib()
    }
    intellijPlatform {
        testFramework(TestFrameworkType.JUnit5)
        bundledModule("intellij.platform.vcs.impl")
    }
}

ksp {
    arg("kotlinx.schema.withSchemaObject", "true")
    arg("kotlinx.schema.visibility", "internal")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

configurations.all {
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}
