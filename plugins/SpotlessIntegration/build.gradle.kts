/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.extensions.excludeCoroutines
import org.jetbrains.intellij.platform.gradle.extensions.excludeKotlinStdlib

plugins {
    id("repo.intellij-plugin")
}

version = "1.1.0"

dependencies {
    intellijPlatform {
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.jetbrains.idea.maven")
    }
    implementation(project("ModelBuilderService"))
    implementation(libs.ktor.client.cio) {
        excludeCoroutines()
        excludeKotlinStdlib()
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    testImplementation("io.ktor:ktor-client-mock:${libs.versions.ktor.get()}") {
        excludeCoroutines()
        excludeKotlinStdlib()
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
}

configurations.all {
    resolutionStrategy.sortArtifacts(ResolutionStrategy.SortOrder.DEPENDENCY_FIRST)
}
