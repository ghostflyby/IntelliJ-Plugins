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

    intellijPlatform {
        bundledModule("intellij.platform.backend")
        bundledModule("intellij.platform.kernel.backend")
        bundledModule("intellij.platform.rpc.backend")
        bundledPlugin("com.intellij.java")
        compatiblePlugin("org.intellij.scala")
    }
}
