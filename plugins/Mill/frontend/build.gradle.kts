/*
 * Copyright (c) 2026 ghostflyby
 * SPDX-FileCopyrightText: 2026 ghostflyby
 * SPDX-License-Identifier: LGPL-3.0-or-later
 */

plugins {
    id("repo.intellij-module")
    id("rpc")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":plugins:Mill:shared"))

    // EditableHintedComboBox widgets, shipped as a content module of this plugin so that a
    // frontend content module can see them (content-module classloaders never see lib/*.jar).
    implementation(project(":modules:intellij-shared"))

    intellijPlatform {
        bundledModule("intellij.platform.frontend")
    }
}
