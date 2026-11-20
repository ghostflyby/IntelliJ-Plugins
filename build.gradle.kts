/*
 * Copyright (c) 2025-2026 ghostflyby
 * SPDX-FileCopyrightText: 2025-2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    java
}

allprojects {
    pluginManager.apply(JavaPlugin::class.java)
    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
            @Suppress("UnstableApiUsage")
            vendor = JvmVendorSpec.JETBRAINS
        }
    }
    repositories { mavenCentral() }
}
repositories {
    mavenCentral()

    // Keep IntelliJ Maven repositories visible from the root build so the IDE can resolve sources
    // without turning the aggregator project itself into an IntelliJ Platform module.
    maven(url = "https://www.jetbrains.com/intellij-repository/releases")
    maven(url = "https://www.jetbrains.com/intellij-repository/snapshots")
    maven(url = "https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")
    maven(url = "https://plugins.jetbrains.com/maven")
}
