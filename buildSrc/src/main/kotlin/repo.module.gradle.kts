/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
        // The Kotlin runtime is provided by the IDE, not Gradle. Pin the language and API
        // surface to the Kotlin bundled with the current platformVersion (2026.1 = 2.3) so
        // newer stdlib/language features fail at compile time instead of at runtime.
        // Bump together with platformVersion in gradle.properties.
        languageVersion = KotlinVersion.KOTLIN_2_3
        apiVersion = KotlinVersion.KOTLIN_2_3
    }
    explicitApi()
}

tasks {
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
