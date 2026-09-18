/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import dev.ghostflyby.buildlogic.BuildLogicProperties
import dev.ghostflyby.buildlogic.BundledKotlinLevel
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    compileOnly(kotlin("stdlib"))
}

// No IntelliJ Platform dependency here: compilation resolves the KGP-provided stdlib while the
// runtime stdlib comes from the IDE. The level is derived from the platform's own version mapping
// (`BundledKotlinLevel`), so it cannot drift from the platform the plugin targets.
val bundledKotlin = providers.gradleProperty(BuildLogicProperties.PLUGIN_SINCE_BUILD)
    .map { sinceBuild -> BundledKotlinLevel.forSinceBuild(sinceBuild.toInt()) }

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        languageVersion.set(bundledKotlin)
        apiVersion.set(bundledKotlin)
    }
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
