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
    // Content-module classloaders see only their own lib/modules/<module-id>.jar (+ platform core
    // and declared parent modules), never the plugin's lib/*.jar. The platform's
    // intellij.libraries.ktor.* core modules cover only part of Ktor (no content-negotiation,
    // resources, or server-resources), so every runtime artifact below is merged into this
    // module's composed jar (IJPL-237683: repackaging into the module jar is the current
    // mechanism). Deliberately excluded: kotlin-stdlib, kotlinx-coroutines (platform-patched),
    // kotlinx-serialization-*, slf4j-api — the platform core classpath provides them.
    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.vcs.impl")

        listOf(
            "io.ktor:ktor-events",
            "io.ktor:ktor-http-cio",
            "io.ktor:ktor-http",
            "io.ktor:ktor-io",
            "io.ktor:ktor-network",
            "io.ktor:ktor-resources",
            "io.ktor:ktor-serialization",
            "io.ktor:ktor-serialization-kotlinx-json",
            "io.ktor:ktor-serialization-kotlinx",
            "io.ktor:ktor-server-cio",
            "io.ktor:ktor-server-content-negotiation",
            "io.ktor:ktor-server-core",
            "io.ktor:ktor-server-resources",
            "io.ktor:ktor-utils",
            "io.ktor:ktor-websockets",
        ).forEach { artifact ->
            pluginComposedModule(dependencies.create("$artifact-jvm:3.4.3"))
        }
        pluginComposedModule(dependencies.create("com.typesafe:config:1.4.5"))
        pluginComposedModule(dependencies.create("org.fusesource.jansi:jansi:2.4.2"))
        pluginComposedModule(dependencies.create("org.yaml:snakeyaml:2.6"))
    }

    implementation(libs.ktor.resources)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.resources)
    implementation(libs.ktor.server.cio)
    implementation(libs.snakeyaml)

    testImplementation(libs.ktor.server.test.host)
}
