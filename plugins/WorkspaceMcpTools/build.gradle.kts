/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("repo.intellij-plugin")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

version = "1.0.4"


dependencies {
    api(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.server.cio)
    ksp(libs.kotlinx.schema.ksp)
    implementation(libs.kotlinx.schema.annotations)
}

ksp {
    arg("kotlinx.schema.rootPackage", "dev.ghostflyby.mcp")
    arg("kotlinx.schema.withSchemaObject", "true")
    arg("kotlinx.schema.visibility", "internal")
}

val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}