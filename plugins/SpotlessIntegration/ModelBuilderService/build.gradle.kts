/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("repo.intellij-lib")
}

kotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_1_8
}

java {
    targetCompatibility = JavaVersion.VERSION_1_8
}

dependencies.intellijPlatform {
    bundledPlugin("com.intellij.gradle")
}
