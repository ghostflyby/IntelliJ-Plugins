/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    compileOnly(kotlin("stdlib"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
        // No IntelliJ Platform dependency here: compilation resolves the KGP-provided stdlib while
        // the runtime stdlib comes from the IDE, so the level has to be pinned here as well.
        configureBundledKotlinLevel(providers)
    }
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
