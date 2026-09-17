/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("repo.intellij-lib")
}

// The tooling model builder is loaded inside arbitrary Gradle daemons, which may run an older JVM
// than the IDE, so the published classes must stay on Java 8 bytecode.
tasks.compileJava {
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}
tasks.compileKotlin {
    compilerOptions.jvmTarget = JvmTarget.JVM_1_8
}

dependencies.intellijPlatform {
    bundledPlugin("com.intellij.gradle")
}
