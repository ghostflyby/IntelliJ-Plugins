/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("repo.intellij-plugin")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

version = "1.0.4"


dependencies {
    api(libs.mcp.kotlin.sdk.server) {
        exclude(group = "io.ktor")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains.kotlin")
    }
    api(libs.ktor.resources) {
        isTransitive = false
    }
    ksp(libs.kotlinx.schema.ksp)
    api(libs.kotlinx.schema.annotations) {
        isTransitive = false
    }
    implementation(project(":modules:intellij-shared"))
    testImplementation(libs.mcp.kotlin.sdk.testing) {
        exclude(group = "io.ktor")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains.kotlin")
    }
    testImplementation(libs.mcp.kotlin.sdk.client) {
        exclude(group = "io.ktor")
        exclude(group = "org.jetbrains.kotlinx")
        exclude(group = "org.jetbrains.kotlin")
    }
    testImplementation(libs.kotlin.stdlib)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
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
