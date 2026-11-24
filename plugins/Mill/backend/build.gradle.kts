/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
}

dependencies {
    implementation(project(":plugins:Mill:shared"))

    // Settings UI widgets (EditableHintedComboBox) come from the shared repo module, which the
    // root build composes into the main plugin jar alongside this backend module.
    implementation(project(":modules:intellij-shared"))

    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledPlugin("com.intellij.java")
        compatiblePlugin("org.intellij.scala")
    }
}
