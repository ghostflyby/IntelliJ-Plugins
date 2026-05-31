/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.module")
    alias(libs.plugins.kotlin.serialization)
}

version = "1.0.4"

dependencies {
    api(libs.mcp.kotlin.sdk.server)
    implementation(libs.ktor.resources)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mcp.kotlin.sdk.testing)
    testImplementation(libs.mcp.kotlin.sdk.client)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
