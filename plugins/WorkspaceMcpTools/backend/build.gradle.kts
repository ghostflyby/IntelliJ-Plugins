/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugins:WorkspaceMcpTools:shared"))

    // GenericPatchApplier and the rest of com.intellij.openapi.diff.impl live in this module.
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.vcs.impl")
    }

    // The backend is an embedded content module composed into the main plugin jar, whose
    // classloader also loads the plugin's lib/*.jar — the ktor family and snakeyaml below stay
    // there (Plugin Content: "All JARs from the /lib folder are automatically added to the
    // classpath"; repackaging them into a content-module jar breaks the plugin verifier).
    implementation(libs.ktor.resources)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.cio)
    implementation(libs.snakeyaml)

    testImplementation(libs.ktor.server.test.host)

    // ktor-server-test-host 3.6.0 pulls kotlinx-coroutines-test 1.11.0, whose API calls are not
    // satisfied by the JetBrains-patched coroutines jar the test framework injects (fork of
    // 1.10.x without runBlockingK$default) — pin the test coroutines back to the fork's baseline.
    configurations.all {
        resolutionStrategy.force(
            "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2",
            "org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2",
        )
    }
}
