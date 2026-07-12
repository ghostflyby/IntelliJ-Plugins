/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-plugin")
}

version = "0.1.0"

dependencies {
    intellijPlatform {
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.gradle")
        bundledPlugin("org.intellij.groovy")
        bundledPlugin("org.toml.lang")
        bundledPlugin("org.jetbrains.kotlin")
        bundledPlugin("org.jetbrains.plugins.gradle")
        testBundledPlugin("org.intellij.groovy")
        testBundledPlugin("com.intellij.properties")
        testBundledPlugin("org.jetbrains.idea.gradle.dsl")
        testBundledPlugin("org.jetbrains.idea.reposearch")
    }
    implementation(project("ModelBuilderService"))
}
