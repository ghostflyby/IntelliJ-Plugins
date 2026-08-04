/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-plugin")
}

version = "0.0.1"

dependencies {
    implementation(project(":modules:intellij-shared"))

    intellijPlatform {
        pluginComposedModule(project(":modules:intellij-shared"))
        bundledPlugin("com.intellij.java")
        compatiblePlugin("org.intellij.scala")
    }
}
