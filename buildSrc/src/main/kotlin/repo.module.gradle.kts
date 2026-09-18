/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import dev.ghostflyby.buildlogic.BuildLogicProperties
import dev.ghostflyby.buildlogic.BundledKotlinLevel
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    java
    alias(libs.plugins.kotlin)
}

group = providers.gradleProperty("pluginGroup").get()

// The Kotlin runtime comes from the IDE, so compile against the Kotlin bundled with the oldest
// supported platform: newer stdlib APIs then fail the build instead of surfacing as a
// NoSuchMethodError at runtime. The level is derived from the platform's own version mapping, so
// a platform bump needs no change here.
val bundledKotlin = providers.gradleProperty(BuildLogicProperties.PLUGIN_SINCE_BUILD)
    .map { sinceBuild -> BundledKotlinLevel.forSinceBuild(sinceBuild.toInt()) }

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("21")
        jvmDefault = JvmDefaultMode.NO_COMPATIBILITY
        languageVersion.set(bundledKotlin)
        apiVersion.set(bundledKotlin)
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
