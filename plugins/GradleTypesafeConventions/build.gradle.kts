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
        testBundledPlugin("org.jetbrains.kotlin")
        testBundledPlugin("org.jetbrains.plugins.gradle")
        testBundledPlugin("org.intellij.groovy")
        testBundledPlugin("org.toml.lang")
        testBundledPlugin("com.intellij.properties")
        testBundledPlugin("org.jetbrains.idea.reposearch")
    }
    implementation(project("ModelBuilderService"))
}
