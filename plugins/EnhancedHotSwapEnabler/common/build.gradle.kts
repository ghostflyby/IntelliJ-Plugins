/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

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
        // No IntelliJ Platform dependency here: compilation resolves the KGP-provided
        // stdlib while the runtime stdlib comes from the IDE (2026.1 = Kotlin 2.3).
        // apiVersion turns usage of newer stdlib APIs into a compile error instead of a
        // runtime NoSuchMethodError. Bump together with platformVersion in gradle.properties.
        languageVersion = KotlinVersion.KOTLIN_2_3
        apiVersion = KotlinVersion.KOTLIN_2_3
    }
    explicitApi()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
