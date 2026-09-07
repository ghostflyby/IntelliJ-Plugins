/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    alias(libs.plugins.kotlin)
}

group = providers.gradleProperty("pluginGroup").get()

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
    }
    explicitApi()
}

tasks {
    test {
        systemProperty("java.awt.headless", true)
    }
    withType<Test> {
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
            events("failed", "skipped")
        }
    }
    processResources {
        from(rootProject.file("LICENSE"))
    }
}
repositories {
    mavenCentral()
}
