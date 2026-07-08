/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.extensions.excludeKotlinStdlib
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    id("repo.module")
    alias(libs.plugins.intellij.platform.base)
    alias(libs.plugins.kover)
}

@OptIn(ExperimentalAbiValidation::class)
kotlin {
    abiValidation()
}

repositories {
    intellijPlatform { defaultRepositories() }
}

val buildLogic = extensions.create<BuildLogicSettings>("buildLogic")

afterEvaluate {
    dependencies.intellijPlatform {
        val relative = buildLogic.platformType.map { ".idea/intellij/${it.code}" }
        val localPlatform = rootProject.file(relative.get())
        if (localPlatform.exists()) {
            local(localPlatform.readText())
        } else {
            create(buildLogic.platformType, buildLogic.platformVersion) {
                useCache = true
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher) {
        excludeKotlinStdlib()
    }
    testImplementation(libs.opentest4j)
    intellijPlatform {
        testFramework(TestFrameworkType.JUnit5)
        testFramework(TestFrameworkType.Platform)
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    systemProperty("java.awt.headless", true)
}
