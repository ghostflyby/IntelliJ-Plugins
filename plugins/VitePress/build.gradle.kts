/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-plugin")
    alias(libs.plugins.kotlin.serialization)
}

version = "1.1.0"

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            untilBuild = "261.*"
        }
    }
}

dependencies {
    implementation(project(":modules:intellij-shared"))
}

dependencies {
    intellijPlatform {
        pluginComposedModule(project(":modules:intellij-shared"))
        bundledPlugin("org.intellij.plugins.markdown")
        bundledPlugin("org.jetbrains.plugins.vue")
    }
}